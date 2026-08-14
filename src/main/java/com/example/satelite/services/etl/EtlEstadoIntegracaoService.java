package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.models.QuarentenaEventoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.repositories.QuarentenaEventoRepository;
import com.example.satelite.services.ResultadoIntegracao;

@Service
public class EtlEstadoIntegracaoService {

    private static final String DESTINO_PPG = "PPG";
    private static final String STATUS_IGNORADO = ResultadoIntegracao.STATUS_IGNORADO;
    private static final String STATUS_ENVIADO = ResultadoIntegracao.STATUS_ENVIADO;
    private static final String STATUS_ERRO_DESTINO = ResultadoIntegracao.STATUS_ERRO_DESTINO;
    private static final String STATUS_SUCESSO = ResultadoIntegracao.STATUS_SUCESSO;
    private static final Set<String> STATUS_FINALIZADOS_SEM_REENVIO = Set.of(STATUS_ENVIADO, STATUS_IGNORADO);

    private final LogIntegracaoRepository logIntegracaoRepository;
    private final AuditoriaDataHoraService auditoriaDataHoraService;
    private final QuarentenaEventoRepository quarentenaEventoRepository;

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

    public LogIntegracaoModel salvar(LogIntegracaoModel logIntegracao) {
        LogIntegracaoModel salvo = logIntegracaoRepository.save(logIntegracao);
        registrarEventosQuarentena(salvo);
        return salvo;
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
                        .findTopBySistemaDestinoAndChaveCteOrderByDataProcessamentoDescIdDesc(destino, chaveCte);
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
        );
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

        logIntegracao.setStatus(resultado.status());
        logIntegracao.setStatusDados(statusDadosNovo);
        logIntegracao.setStatusCanhoto(statusCanhotoNovo);
        logIntegracao.setMensagemErroDados(resultado.mensagemErroDados());
        logIntegracao.setMensagemErroCanhoto(resultado.mensagemErroCanhoto());
        logIntegracao.setErro(montarMensagemErroGeral(resultado));
        logIntegracao.setDataProcessamento(agora);

        if (deveAtualizarDataProcessamento(statusDadosAnterior, statusDadosNovo)) {
            logIntegracao.setDataProcessamentoDados(agora);
            logIntegracao.setTentativasDados(incrementar(logIntegracao.getTentativasDados()));
        }

        if (deveAtualizarDataProcessamento(statusCanhotoAnterior, statusCanhotoNovo)) {
            logIntegracao.setDataProcessamentoCanhoto(agora);
            logIntegracao.setTentativasCanhoto(incrementar(logIntegracao.getTentativasCanhoto()));
        }

        logIntegracao.setEstavaEmQuarentena(estavaEmQuarentena);
        logIntegracao.setResultadoDaRepescagem(estavaEmQuarentena ? resultado.status() : null);
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
        return logIntegracao != null
                && STATUS_ERRO_DESTINO.equals(logIntegracao.getStatus())
                && (valorTentativas(logIntegracao.getTentativasDados()) >= 3
                || valorTentativas(logIntegracao.getTentativasCanhoto()) >= 3);
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
