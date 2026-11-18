package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import com.example.satelite.policy.AcaoEtapaIntegracao;
import com.example.satelite.repositories.IntegracaoEventoRepository;
import com.example.satelite.utils.ContextoCicloIntegracao;

import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.models.QuarentenaEventoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.repositories.QuarentenaEventoRepository;
import com.example.satelite.services.ResultadoIntegracao;

@Service
public class EtlEstadoIntegracaoService {

    private static final String DESTINO_PPG = "PPG";
    private static final String DESTINO_VEDACIT = "VEDACIT";
    private static final String STATUS_IGNORADO = ResultadoIntegracao.STATUS_IGNORADO;
    private static final String STATUS_ENVIADO = ResultadoIntegracao.STATUS_ENVIADO;
    private static final String STATUS_ERRO_DESTINO = ResultadoIntegracao.STATUS_ERRO_DESTINO;
    private static final String STATUS_SUCESSO = ResultadoIntegracao.STATUS_SUCESSO;
    private static final Set<String> STATUS_FINALIZADOS_SEM_REENVIO = Set.of(STATUS_ENVIADO, STATUS_IGNORADO);

    private final LogIntegracaoRepository logIntegracaoRepository;
    private final AuditoriaDataHoraService auditoriaDataHoraService;
    private final QuarentenaEventoRepository quarentenaEventoRepository;

    @Autowired
    private IntegracaoEventoRepository eventosRepository;
    @Value("${VEDACIT_XML_SOURCE_RETRY_COOLDOWN_MS:1800000}")
    private long intervaloXmlMs = 1800000;
    @Value("${INTEGRATION_DOCUMENT_RETRY_COOLDOWN_MS:1800000}")
    private long intervaloDocumentoMs = 1800000;

    @Autowired
    public EtlEstadoIntegracaoService(
            LogIntegracaoRepository logIntegracaoRepository,
            AuditoriaDataHoraService auditoriaDataHoraService,
            QuarentenaEventoRepository quarentenaEventoRepository
    ) {
        this.logIntegracaoRepository = logIntegracaoRepository;
        this.auditoriaDataHoraService = auditoriaDataHoraService;
        this.quarentenaEventoRepository = quarentenaEventoRepository;
    }

    public EtlEstadoIntegracaoService(LogIntegracaoRepository logIntegracaoRepository) {
        this(logIntegracaoRepository, new AuditoriaDataHoraService(logIntegracaoRepository), null);
    }

    public EtlEstadoIntegracaoService(
            LogIntegracaoRepository logIntegracaoRepository,
            AuditoriaDataHoraService auditoriaDataHoraService
    ) {
        this(logIntegracaoRepository, auditoriaDataHoraService, null);
    }

    @Transactional
    public LogIntegracaoModel salvar(LogIntegracaoModel logIntegracao) {
        prepararAcao(logIntegracao, true);
        prepararAcao(logIntegracao, false);
        LogIntegracaoModel salvo = logIntegracaoRepository.save(logIntegracao);
        registrarEventosQuarentena(salvo);
        // Construtores legados sem Spring são usados pela suíte unitária isolada.
        // Em produção a dependência é obrigatória e a gravação participa desta transação.
        if (eventosRepository != null) {
            registrarAvaliacao(logIntegracao, salvo.getId(), true);
            registrarAvaliacao(logIntegracao, salvo.getId(), false);
        }
        return salvo;
    }

    /** Conciliação técnica de XML já aceito, sem registrar tentativa de transmissão. */
    @Transactional
    public LogIntegracaoModel salvarConciliacaoXmlSemEnvio(
            LogIntegracaoModel registro, Long logFonte, Long eventoEntrega) {
        if (eventosRepository == null || logFonte == null || eventoEntrega == null
                || !DESTINO_VEDACIT.equals(registro.getSistemaDestino())
                || !STATUS_SUCESSO.equals(registro.getStatusDados())
                || registro.getDataProcessamentoDados() == null) {
            throw new IllegalArgumentException("Provas técnicas de conciliação incompletas");
        }
        LogIntegracaoModel salvo = salvar(registro);
        eventosRepository.registrar(new IntegracaoEventoRepository.Evento(java.util.UUID.randomUUID(),
                ContextoCicloIntegracao.atual(), salvo.getId(), DESTINO_VEDACIT, "DADOS",
                "CTE:" + salvo.getChaveCte(), "AVALIACAO", "CONFIRMADO",
                "XML_CONCILIADO_LOG_FONTE:" + logFonte + ";ENTREGA_EVENTO:" + eventoEntrega,
                agoraAuditoria(), null));
        return salvo;
    }

    private void prepararAcao(LogIntegracaoModel log, boolean dados) {
        LocalDateTime quando = dados ? log.getAvaliacaoDadosEm() : log.getAvaliacaoCanhotoEm();
        if (quando == null) return;
        var acao = AcaoEtapaIntegracao.classificar(dados ? log.getStatusDados() : log.getStatusCanhoto(),
                dados ? null : log.getCanhotoClassificacaoOperacional(),
                dados ? log.getMensagemErroDados() : log.getMensagemErroCanhoto());
        boolean pendente = acao != AcaoEtapaIntegracao.CONFIRMADO && acao != AcaoEtapaIntegracao.IGNORADO;
        var proxima = acao.permiteReavaliacaoAutomatica()
                ? quando.plusNanos(Math.max(1000, Math.min(86_400_000, dados ? intervaloXmlMs : intervaloDocumentoMs)) * 1_000_000L) : null;
        if (dados) {
            log.setDadosAcaoOperacional(acao.name()); log.setDadosProximaAvaliacaoEm(proxima);
            log.setDadosPendenteDesde(!pendente ? null : log.getDadosPendenteDesde() == null ? quando : log.getDadosPendenteDesde());
        } else {
            log.setCanhotoAcaoPainel(acao.name()); log.setCanhotoProximaAvaliacaoEm(proxima);
            log.setCanhotoPendenteDesde(!pendente ? null : log.getCanhotoPendenteDesde() == null ? quando : log.getCanhotoPendenteDesde());
        }
    }

    private void registrarAvaliacao(LogIntegracaoModel log, Long id, boolean dados) {
        var eventoId = dados ? log.getAvaliacaoDadosId() : log.getAvaliacaoCanhotoId();
        if (eventoId == null) return;
        String etapa = dados ? "DADOS" : "COMPROVANTE";
        String cte = !dados && log.getCanhotoChaveCteEfetiva() != null && !log.getCanhotoChaveCteEfetiva().isBlank()
                ? log.getCanhotoChaveCteEfetiva() : log.getChaveCte();
        var acao = AcaoEtapaIntegracao.valueOf(dados ? log.getDadosAcaoOperacional() : log.getCanhotoAcaoPainel());
        eventosRepository.registrar(new IntegracaoEventoRepository.Evento(eventoId, log.getAvaliacaoCicloId(), id,
                log.getSistemaDestino(), etapa,
                IntegracaoEventoRepository.documento(log.getSistemaDestino(), etapa, log.getChaveNfe(), cte, log.getOccurrenceId(), id),
                "AVALIACAO", acao.name(), AcaoEtapaIntegracao.motivo(dados ? log.getMensagemErroDados() : log.getMensagemErroCanhoto(), acao),
                dados ? log.getAvaliacaoDadosEm() : log.getAvaliacaoCanhotoEm(),
                dados ? log.getDadosProximaAvaliacaoEm() : log.getCanhotoProximaAvaliacaoEm()));
    }

    /** Revisita sem chamada externa: registra a decisão, sem mover a data do erro ou a agenda. */
    public void registrarReavaliacaoXmlSemEnvio(LogIntegracaoModel log) {
        if (eventosRepository == null || log == null || log.getId() == null) return;
        var acao = AcaoEtapaIntegracao.classificar(log.getStatusDados(), null, log.getMensagemErroDados());
        eventosRepository.registrar(new IntegracaoEventoRepository.Evento(java.util.UUID.randomUUID(),
                ContextoCicloIntegracao.atual(), log.getId(), log.getSistemaDestino(), "DADOS",
                IntegracaoEventoRepository.documento(log.getSistemaDestino(), "DADOS", log.getChaveNfe(), log.getChaveCte(), log.getOccurrenceId(), log.getId()),
                "AVALIACAO", acao.name(), AcaoEtapaIntegracao.motivo(log.getMensagemErroDados(), acao),
                agoraAuditoria(), log.getDadosProximaAvaliacaoEm()));
    }

    public List<LogIntegracaoModel> buscarPendenciasCanhoto(String destino, String statusCanhoto) {
        return logIntegracaoRepository.findBySistemaDestinoAndStatusCanhotoOrderByDataProcessamentoAscIdAsc(
                destino,
                statusCanhoto
        );
    }

    public Optional<LogIntegracaoModel> buscarLogIntegracaoExistente(String destino, EslOcorrenciaDTO ocorrencia) {
        if ("VEDACIT".equals(destino)) {
            String chaveCte = obterChaveCte(ocorrencia);
            if (chaveCte != null) {
                Optional<LogIntegracaoModel> porCte = logIntegracaoRepository
                        .findTopBySistemaDestinoAndChaveNfeAndChaveCteAndArquivadoFalseOrderByDataProcessamentoDescIdDesc(
                                destino, obterChaveNfe(ocorrencia), chaveCte);
                if (porCte.isPresent()) {
                    return porCte;
                }
            }
        }

        Long occurrenceId = obterOccurrenceId(ocorrencia);
        if (occurrenceId == null) {
            return Optional.empty();
        }

        return logIntegracaoRepository.findTopBySistemaDestinoAndOccurrenceIdOrderByDataProcessamentoDescIdDesc(
                destino,
                occurrenceId
        ).filter(l -> !Boolean.TRUE.equals(l.getArquivado()));
    }

    public Optional<LogIntegracaoModel> buscarAtivoPorId(Long id) {
        return id == null ? Optional.empty() : logIntegracaoRepository.findById(id)
                .filter(l -> !Boolean.TRUE.equals(l.getArquivado()));
    }

    public boolean xmlVedacitConfirmado(String chaveCte) {
        return chaveCte != null && logIntegracaoRepository
                .existsBySistemaDestinoAndChaveCteAndStatusDados("VEDACIT", chaveCte, "SUCESSO");
    }

    public boolean xmlVedacitSucessoSemData(String chaveCte) {
        return chaveCte != null && logIntegracaoRepository.existsXmlVedacitSucessoSemData(chaveCte);
    }

    public boolean finalizadoSemReenvio(LogIntegracaoModel logIntegracao) {
        return logIntegracao != null && STATUS_FINALIZADOS_SEM_REENVIO.contains(logIntegracao.getStatus());
    }

    public boolean deveReprocessarIgnoradoSemEnvio(String destino, LogIntegracaoModel logIntegracao) {
        return "SELIA".equals(destino)
                && logIntegracao != null
                && STATUS_IGNORADO.equals(logIntegracao.getStatus())
                && textoVazio(logIntegracao.getRequestPayload())
                && textoVazio(logIntegracao.getResponsePayload());
    }

    public LogIntegracaoModel criarLogComStatus(
            String destino,
            Long cursorNextId,
            EslOcorrenciaDTO ocorrencia,
            String status
    ) {
        return LogIntegracaoModel.builder()
                .occurrenceId(obterOccurrenceId(ocorrencia))
                .chaveNfe(obterChaveNfe(ocorrencia))
                .chaveCte(obterChaveCte(ocorrencia))
                .freightId(ocorrencia != null && ocorrencia.freight() != null ? ocorrencia.freight().id() : null)
                .cursorNextId(cursorNextId)
                .status(status)
                .statusDados(status)
                .statusCanhoto(status)
                .tentativasDados(0)
                .tentativasCanhoto(0)
                .sistemaDestino(destino)
                .dataProcessamento(agoraAuditoria())
                .build();
    }

    private boolean textoVazio(String valor) {
        return valor == null || valor.isBlank();
    }

    public void aplicarResultadoIntegracao(LogIntegracaoModel logIntegracao, ResultadoIntegracao resultado) {
        boolean estavaEmQuarentena = estaEmQuarentena(logIntegracao);
        LocalDateTime agora = agoraAuditoria();
        String statusDadosAnterior = logIntegracao.getStatusDados();
        String statusCanhotoAnterior = logIntegracao.getStatusCanhoto();
        String statusDadosNovo = resultado.statusDados() != null ? resultado.statusDados() : statusDadosAnterior;
        String statusCanhotoNovo = resultado.statusCanhoto() != null ? resultado.statusCanhoto() : statusCanhotoAnterior;
        boolean preservarComprovante = "VEDACIT".equals(logIntegracao.getSistemaDestino())
                && STATUS_SUCESSO.equals(statusCanhotoAnterior);
        if (preservarComprovante) statusCanhotoNovo = statusCanhotoAnterior;

        logIntegracao.setStatus(preservarComprovante && STATUS_SUCESSO.equals(statusDadosNovo)
                ? ResultadoIntegracao.STATUS_ENVIADO : resultado.status());
        logIntegracao.setStatusDados(statusDadosNovo);
        logIntegracao.setStatusCanhoto(statusCanhotoNovo);
        logIntegracao.setMensagemErroDados(resultado.mensagemErroDados());
        if (!preservarComprovante && resultado.statusCanhoto() != null)
            logIntegracao.setMensagemErroCanhoto(resultado.mensagemErroCanhoto());
        logIntegracao.setErro(preservarComprovante ? resultado.mensagemErroDados() : montarMensagemErroGeral(resultado));
        logIntegracao.setDataProcessamento(agora);

        if (deveAtualizarDataProcessamento(statusDadosAnterior, statusDadosNovo)) {
            logIntegracao.setDataProcessamentoDados(agora);
            logIntegracao.setTentativasDados(incrementar(logIntegracao.getTentativasDados()));
        }

        if (!preservarComprovante && resultado.statusCanhoto() != null
                && deveAtualizarDataProcessamento(statusCanhotoAnterior, statusCanhotoNovo)) {
            logIntegracao.setDataProcessamentoCanhoto(agora);
            logIntegracao.setTentativasCanhoto(incrementar(logIntegracao.getTentativasCanhoto()));
        }

        logIntegracao.setEstavaEmQuarentena(estavaEmQuarentena);
        logIntegracao.setResultadoDaRepescagem(estavaEmQuarentena ? resultado.status() : null);
        logIntegracao.setAvaliacaoCicloId(ContextoCicloIntegracao.atual());
        logIntegracao.setAvaliacaoDadosId(null);
        logIntegracao.setAvaliacaoDadosEm(null);
        logIntegracao.setAvaliacaoCanhotoId(null);
        logIntegracao.setAvaliacaoCanhotoEm(null);
        if (resultado.statusDados() != null && !(STATUS_SUCESSO.equals(statusDadosAnterior) && STATUS_SUCESSO.equals(statusDadosNovo))) {
            logIntegracao.setAvaliacaoDadosId(java.util.UUID.randomUUID());
            logIntegracao.setAvaliacaoDadosEm(agora);
        }
        if (!preservarComprovante && resultado.statusCanhoto() != null) {
            logIntegracao.setAvaliacaoCanhotoId(java.util.UUID.randomUUID());
            logIntegracao.setAvaliacaoCanhotoEm(agora);
        }
    }

    public ResultadoRegistro converterResultadoRegistro(ResultadoIntegracao resultado) {
        if (resultado.erro()) {
            return ResultadoRegistro.ERRO;
        }

        if (resultado.foiIgnorado()) {
            return ResultadoRegistro.IGNORADO;
        }

        if (resultado.pendenteFoto()) {
            return ResultadoRegistro.PENDENTE_FOTO;
        }

        if (resultado.pendenteOrigem()) {
            return ResultadoRegistro.PENDENTE_ORIGEM;
        }

        return ResultadoRegistro.ENVIADO;
    }

    public void classificarCanhotoVedacit(
            LogIntegracaoModel logIntegracao,
            ClassificacaoOperacionalCanhotoVedacit classificacao
    ) {
        if (logIntegracao == null || classificacao == null) {
            return;
        }
        if (STATUS_SUCESSO.equals(logIntegracao.getStatusCanhoto())
                && classificacao != ClassificacaoOperacionalCanhotoVedacit.SUCESSO) return;
        logIntegracao.setCanhotoClassificacaoOperacional(classificacao.name());
        logIntegracao.setCanhotoClassificadoEm(agoraAuditoria());
    }

    public boolean jaExisteCanhotoVedacitEnviado(String chaveNfe) {
        return logIntegracaoRepository.existsBySistemaDestinoAndChaveNfeAndStatusCanhoto(
                "VEDACIT", chaveNfe, ResultadoIntegracao.STATUS_SUCESSO
        );
    }

    public boolean canhotoVedacitSucessoRegistradoPorPar(String chaveNfe, String chaveCte) {
        return logIntegracaoRepository.existsConfirmacaoDuravelComprovante(chaveNfe, chaveCte)
                || logIntegracaoRepository.existsCanhotoVedacitSucessoPorPar(chaveNfe, chaveCte);
    }

    public boolean canhotoVedacitRetidoPorPar(String chaveNfe, String chaveCte) {
        return logIntegracaoRepository.existsCanhotoVedacitRetidoPorPar(chaveNfe, chaveCte);
    }

    /** Antes do efeito remoto: uma queda do processo exige conferência, nunca reenvio cego. */
    @Transactional
    public void registrarInicioEnvioCanhoto(LogIntegracaoModel registro) {
        registro.setStatusCanhoto("EM_PROCESSAMENTO");
        registro.setMensagemErroCanhoto("ENVIO_INICIADO_SEM_RESULTADO: conferir destino se o processo for interrompido.");
        registro.setCanhotoClassificacaoOperacional(ClassificacaoOperacionalCanhotoVedacit.TIMEOUT_AMBIGUO.name());
        registro.setCanhotoClassificadoEm(agoraAuditoria());
        salvar(registro);
    }

    /** Propaga somente o resultado do mesmo canhoto; não altera chave CT-e histórica. */
    @Transactional
    public void marcarCanhotosVedacitRelacionadosComoSucesso(
            String chaveNfe, String chaveCteEfetiva, String tipo, String motivo
    ) {
        List<LogIntegracaoModel> relacionados = logIntegracaoRepository
                .findBySistemaDestinoAndChaveNfeOrderByDataProcessamentoAscIdAsc("VEDACIT", chaveNfe);
        for (LogIntegracaoModel relacionado : relacionados) {
            if (Boolean.TRUE.equals(relacionado.getArquivado())) continue;
            if (!java.util.Objects.equals(chaveCteEfetiva, relacionado.getChaveCte())
                    && !java.util.Objects.equals(chaveCteEfetiva, relacionado.getCanhotoChaveCteEfetiva())) continue;
            if (!ResultadoIntegracao.STATUS_SUCESSO.equals(relacionado.getStatusDados())
                    || ResultadoIntegracao.STATUS_SUCESSO.equals(relacionado.getStatusCanhoto())) {
                continue;
            }
            relacionado.setCanhotoChaveCteEfetiva(chaveCteEfetiva);
            relacionado.setCanhotoReconciliacaoTipo(tipo);
            relacionado.setCanhotoReconciliacaoMotivo(motivo);
            aplicarResultadoIntegracao(relacionado, ResultadoIntegracao.vedacitConcluido(
                    ResultadoIntegracao.STATUS_SUCESSO, ResultadoIntegracao.STATUS_SUCESSO
            ));
            salvar(relacionado);
        }
    }

    public List<LogIntegracaoModel> buscarXmlFalhaOrigem(LocalDateTime antes, int limite) {
        return logIntegracaoRepository.findXmlFalhaOrigemParaRecuperacao(antes,
                org.springframework.data.domain.PageRequest.of(0, limite));
    }

    public void aplicarResultadoXml(LogIntegracaoModel registro, ResultadoIntegracao resultado) {
        String status = resultado.status();
        if (STATUS_SUCESSO.equals(resultado.statusDados()) && !STATUS_SUCESSO.equals(registro.getStatusCanhoto()))
            status = ResultadoIntegracao.STATUS_PARCIAL;
        boolean ausente = ResultadoIntegracao.STATUS_PENDENTE_ORIGEM.equals(resultado.statusDados());
        // Etapa ausente no resultado não recebe data, tentativa ou mensagem nova.
        aplicarResultadoIntegracao(registro, new ResultadoIntegracao(status, resultado.statusDados(),
                null, ausente ? "ORIGEM_XML_AUSENTE" : resultado.mensagemErroDados(), registro.getMensagemErroCanhoto()));
        if (ausente) registro.setDataProcessamentoDados(agoraAuditoria());
    }

    public ResultadoIntegracao criarResultadoErroGenerico(String destino, Exception e) {
        String mensagem = e.getMessage();
        if (DESTINO_PPG.equals(destino)) {
            return new ResultadoIntegracao(
                    STATUS_ERRO_DESTINO,
                    STATUS_ERRO_DESTINO,
                    STATUS_ERRO_DESTINO,
                    mensagem,
                    mensagem
            );
        }

        return ResultadoIntegracao.erroDados(mensagem);
    }

    public String statusDadosAtualOuSucesso(LogIntegracaoModel logIntegracao) {
        if (logIntegracao.getStatusDados() == null || logIntegracao.getStatusDados().isBlank()) {
            return STATUS_SUCESSO;
        }

        return logIntegracao.getStatusDados();
    }

    public boolean statusSucesso(String status) {
        return STATUS_SUCESSO.equals(status);
    }

    public LocalDateTime agoraAuditoria() {
        return auditoriaDataHoraService.agora();
    }

    boolean deveAtualizarDataProcessamento(String statusAnterior, String statusNovo) {
        return statusNovo != null
                && !ResultadoIntegracao.STATUS_NAO_APLICAVEL.equals(statusNovo)
                && !ResultadoIntegracao.STATUS_PENDENTE_FOTO.equals(statusNovo)
                && !ResultadoIntegracao.STATUS_PENDENTE_ORIGEM.equals(statusNovo)
                && (STATUS_ERRO_DESTINO.equals(statusNovo) || !statusNovo.equals(statusAnterior));
    }

    Integer incrementar(Integer valorAtual) {
        return valorAtual == null ? 1 : valorAtual + 1;
    }

    String montarMensagemErroGeral(ResultadoIntegracao resultado) {
        if (resultado.mensagemErroDados() != null && resultado.mensagemErroCanhoto() != null) {
            return resultado.mensagemErroDados() + " | " + resultado.mensagemErroCanhoto();
        }

        if (resultado.mensagemErroDados() != null) {
            return resultado.mensagemErroDados();
        }

        return resultado.mensagemErroCanhoto();
    }

    private void registrarEventosQuarentena(LogIntegracaoModel logIntegracao) {
        if (quarentenaEventoRepository == null || logIntegracao == null || logIntegracao.getId() == null) {
            return;
        }

        if (estaEmQuarentena(logIntegracao)
                && !quarentenaEventoRepository.existsByLogIntegracaoIdAndTipoEvento(
                        logIntegracao.getId(), "ENTRADA_QUARENTENA")) {
            quarentenaEventoRepository.save(evento(logIntegracao, "ENTRADA_QUARENTENA", "PENDENTE"));
        }

        if (logIntegracao.isEstavaEmQuarentena() && logIntegracao.getResultadoDaRepescagem() != null) {
            quarentenaEventoRepository.save(evento(
                    logIntegracao,
                    "REPESCAGEM",
                    resultadoRepescagem(logIntegracao.getResultadoDaRepescagem())
            ));
            logIntegracao.setEstavaEmQuarentena(false);
            logIntegracao.setResultadoDaRepescagem(null);
        }
    }

    private QuarentenaEventoModel evento(LogIntegracaoModel logIntegracao, String tipoEvento, String resultado) {
        return QuarentenaEventoModel.builder()
                .logIntegracaoId(logIntegracao.getId())
                .tipoEvento(tipoEvento)
                .resultado(resultado)
                .etapa(etapaAfetada(logIntegracao))
                .mensagem(logIntegracao.getErro())
                .dataEvento(logIntegracao.getDataProcessamento() != null
                        ? logIntegracao.getDataProcessamento() : agoraAuditoria())
                .build();
    }

    private boolean estaEmQuarentena(LogIntegracaoModel logIntegracao) {
        if (logIntegracao == null || !STATUS_ERRO_DESTINO.equals(logIntegracao.getStatus())
                || (valorTentativas(logIntegracao.getTentativasDados()) < 3
                && valorTentativas(logIntegracao.getTentativasCanhoto()) < 3)) {
            return false;
        }
        String classificacao = logIntegracao.getCanhotoClassificacaoOperacional();
        return classificacao == null || classificacao.isBlank()
                || ClassificacaoOperacionalCanhotoVedacit.PENDENTE_TECNICO.name().equals(classificacao);
    }

    private String resultadoRepescagem(String status) {
        if (STATUS_ENVIADO.equals(status) || STATUS_SUCESSO.equals(status)) {
            return "SUCESSO";
        }
        if (STATUS_ERRO_DESTINO.equals(status)) {
            return "ERRO";
        }
        return "PENDENTE";
    }

    private String etapaAfetada(LogIntegracaoModel logIntegracao) {
        boolean dados = STATUS_ERRO_DESTINO.equals(logIntegracao.getStatusDados());
        boolean canhoto = STATUS_ERRO_DESTINO.equals(logIntegracao.getStatusCanhoto());
        if (dados && canhoto) {
            return "DADOS_E_COMPROVANTE";
        }
        return dados ? "DADOS" : canhoto ? "COMPROVANTE" : "GERAL";
    }

    private int valorTentativas(Integer tentativas) {
        return tentativas != null ? tentativas : 0;
    }

    private Long obterOccurrenceId(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia == null) {
            return null;
        }

        return ocorrencia.id();
    }

    private String obterChaveNfe(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia == null || ocorrencia.invoice() == null) {
            return null;
        }

        return ocorrencia.invoice().key();
    }

    private String obterChaveCte(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia == null || ocorrencia.freight() == null || ocorrencia.freight().cteKey() == null) {
            return null;
        }

        String chaveCte = ocorrencia.freight().cteKey().trim();
        return chaveCte.isEmpty() ? null : chaveCte;
    }
}
