package com.example.satelite.services.etl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.selia.SeliaIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

@Service
public class EtlRepescagemService {

    private static final Logger log = LoggerFactory.getLogger(EtlRepescagemService.class);
    private static final Logger logDetalheSftpVedacit = LoggerFactory.getLogger("satelite.vedacit.sftp.detail");

    private static final String DESTINO_PPG = "PPG";
    private static final String DESTINO_SELIA = "SELIA";
    private static final String DESTINO_VEDACIT = "VEDACIT";
    private static final String STATUS_ERRO_DESTINO = "ERRO_DESTINO";
    private static final Pattern CHAVE_CTE_NA_MENSAGEM = Pattern.compile("[?&](?:cte_)?key=([0-9]{44})");

    private final LogIntegracaoRepository logIntegracaoRepository;
    private final EtlRegistroService etlRegistroService;
    private final EtlEstadoIntegracaoService etlEstadoIntegracaoService;
    private final PpgIntegrationService ppgIntegrationService;
    private final VedacitIntegrationService vedacitIntegrationService;
    private final SeliaIntegrationService seliaIntegrationService;

    @Value("${RODOGARCIA_TOKEN_PPG}")
    private String tokenPpgEsl;

    @Value("${RODOGARCIA_TOKEN_VEDACIT}")
    private String tokenVedacitEsl;

    @Value("${RODOGARCIA_TOKEN_SELIA:}")
    private String tokenSeliaEsl;

    @Value("${ETL_REPESCAGEM_INTERVAL_MS:10000}")
    private long intervaloEntreRegistrosMs = 10000;

    @Autowired
    public EtlRepescagemService(
            LogIntegracaoRepository logIntegracaoRepository,
            EtlRegistroService etlRegistroService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            PpgIntegrationService ppgIntegrationService,
            VedacitIntegrationService vedacitIntegrationService,
            SeliaIntegrationService seliaIntegrationService
    ) {
        this.logIntegracaoRepository = logIntegracaoRepository;
        this.etlRegistroService = etlRegistroService;
        this.etlEstadoIntegracaoService = etlEstadoIntegracaoService;
        this.ppgIntegrationService = ppgIntegrationService;
        this.vedacitIntegrationService = vedacitIntegrationService;
        this.seliaIntegrationService = seliaIntegrationService;
    }

    public EtlRepescagemService(
            LogIntegracaoRepository logIntegracaoRepository,
            EtlRegistroService etlRegistroService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            PpgIntegrationService ppgIntegrationService,
            VedacitIntegrationService vedacitIntegrationService
    ) {
        this(
                logIntegracaoRepository,
                etlRegistroService,
                etlEstadoIntegracaoService,
                ppgIntegrationService,
                vedacitIntegrationService,
                null
        );
    }

    public void executarRepescagem(LocalDateTime inicioCiclo) {
        List<LogIntegracaoModel> errosDefinitivos = buscarErrosDefinitivosDoCiclo(inicioCiclo);
        List<LogIntegracaoModel> errosParciaisCanhoto = buscarErrosParciaisCanhotoPendentesRetry();
        List<LogIntegracaoModel> registros = new ArrayList<>(errosDefinitivos.size() + errosParciaisCanhoto.size());
        registros.addAll(errosDefinitivos);
        registros.addAll(errosParciaisCanhoto);

        if (registros.isEmpty()) {
            log.info("🎣 Repescagem: nenhum erro definitivo ou parcial de canhoto pendente encontrado.");
            return;
        }

        log.warn(
                "🎣 Repescagem ativa iniciada para {} registro(s): definitivos_do_ciclo={} parciais_canhoto_retry={}.",
                registros.size(),
                errosDefinitivos.size(),
                errosParciaisCanhoto.size()
        );
        for (int indice = 0; indice < registros.size(); indice++) {
            LogIntegracaoModel registro = registros.get(indice);
            reprocessarRegistro(registro);

            if (indice < registros.size() - 1 && !pausarEntreRegistros()) {
                log.warn("⏹️ Repescagem interrompida antes de concluir todos os registros.");
                return;
            }
        }

        log.warn("🎣 Repescagem ativa finalizada.");
    }

    public ResultadoReprocessamentoCanhotoVedacit reprocessarCanhotosVedacit(int limite) {
        int limiteSeguro = Math.max(1, limite);
        List<LogIntegracaoModel> registros = logIntegracaoRepository.findErrosParciaisCanhotoVedacit(
                PageRequest.of(0, limiteSeguro)
        );
        if (registros == null || registros.isEmpty()) {
            log.info("🎯 [VEDACIT] Nenhum canhoto com erro pendente para reprocessamento cirúrgico.");
            return new ResultadoReprocessamentoCanhotoVedacit(0, 0, 0, 0, 0);
        }

        int enviados = 0;
        int pendentes = 0;
        int erros = 0;
        log.warn("🎯 [VEDACIT] Iniciando reprocessamento cirúrgico de {} canhoto(s).", registros.size());
        for (LogIntegracaoModel registro : registros) {
            ResultadoRegistro resultado = etlRegistroService.reprocessarCanhotoVedacitPorCte(registro);
            if (resultado == ResultadoRegistro.ENVIADO) {
                enviados++;
            } else if (resultado == ResultadoRegistro.PENDENTE_FOTO) {
                pendentes++;
            } else if (resultado.erro()) {
                erros++;
            }
            log.info(
                    "🎯 [VEDACIT] NF {}: resultado do canhoto isolado={}",
                    registro.getChaveNfe(),
                    resultado
            );
        }

        return new ResultadoReprocessamentoCanhotoVedacit(registros.size(), enviados, pendentes, erros, 0);
    }

    private void registrarOrigemSftpDoCanhoto(LogIntegracaoModel registro) {
        registro.setCanhotoOrigem("SFTP");
        etlEstadoIntegracaoService.salvar(registro);
    }

    /**
     * Processa somente pendências de foto cujo XML já foi integrado e cujo CT-e
     * está auditado. O runner correspondente exige fonte SFTP exclusiva, sem
     * fallback para ESL, para evitar carga acidental na origem durante lote.
     */
    public ResultadoReprocessamentoCanhotoVedacit reprocessarCanhotosPendentesFotoSftpVedacit(
            int limite,
            long intervaloEntreItensMs
    ) {
        return reprocessarCanhotosPendentesFotoSftpVedacit(limite, intervaloEntreItensMs, null);
    }

    public ResultadoReprocessamentoCanhotoVedacit reprocessarCanhotosPendentesFotoSftpVedacit(
            int limite,
            long intervaloEntreItensMs,
            List<String> chavesNfeComArquivoSftp
    ) {
        int limiteSeguro = Math.max(1, limite);
        List<LogIntegracaoModel> registros = chavesNfeComArquivoSftp == null
                ? logIntegracaoRepository.findCanhotosPendentesFotoVedacit(PageRequest.of(0, limiteSeguro))
                : chavesNfeComArquivoSftp.isEmpty()
                        ? List.of()
                        : logIntegracaoRepository.findCanhotosPendentesFotoVedacitPorNfes(
                        chavesNfeComArquivoSftp, PageRequest.of(0, limiteSeguro)
                        );
        if (registros == null || registros.isEmpty()) {
            log.info("🎯 [VEDACIT] Nenhum canhoto PENDENTE_FOTO com CT-e elegível no lote SFTP.");
            return new ResultadoReprocessamentoCanhotoVedacit(0, 0, 0, 0, 0);
        }

        int enviados = 0;
        int pendentes = 0;
        int erros = 0;
        int ignorados = 0;
        int processados = 0;
        log.info("[INICIO] [VEDACIT][SFTP] Lote | itens={} | origem=SFTP | fallback_ESL=desligado", registros.size());
        for (int indice = 0; indice < registros.size(); indice++) {
            LogIntegracaoModel registro = registros.get(indice);
            long inicioItem = System.nanoTime();
            registrarOrigemSftpDoCanhoto(registro);
            ResultadoRegistro resultado = etlRegistroService.reprocessarCanhotoVedacitPorCte(registro);
            if (resultado == ResultadoRegistro.ENVIADO) {
                enviados++;
            } else if (resultado == ResultadoRegistro.PENDENTE_FOTO) {
                pendentes++;
            } else if (resultado.erro()) {
                erros++;
            } else {
                ignorados++;
            }
            processados++;
            logarProgressoSftp(
                    indice + 1, registros.size(), registro, resultado,
                    Duration.ofNanos(System.nanoTime() - inicioItem),
                    enviados, pendentes, erros, ignorados
            );

            if (indice < registros.size() - 1 && !pausarEntreRegistros(intervaloEntreItensMs)) {
                log.warn("⏹️ [VEDACIT] Lote SFTP interrompido antes de concluir os candidatos.");
                break;
            }
        }

        return new ResultadoReprocessamentoCanhotoVedacit(processados, enviados, pendentes, erros, ignorados);
    }

    public ResultadoReprocessamentoCanhotoVedacit reprocessarTimeoutsAmbiguosSftpVedacit(
            int limite,
            int limiteTentativas,
            long intervaloEntreItensMs
    ) {
        int limiteSeguro = Math.max(1, Math.min(limite, 10));
        int tentativasSeguras = Math.max(1, limiteTentativas);
        List<LogIntegracaoModel> registros = logIntegracaoRepository.findTimeoutsAmbiguosCanhotoSftpVedacit(
                tentativasSeguras,
                PageRequest.of(0, limiteSeguro)
        );
        if (registros == null || registros.isEmpty()) {
            log.info("[INFO] [VEDACIT][SFTP] Nenhum timeout ambíguo elegível para retentativa controlada.");
            return new ResultadoReprocessamentoCanhotoVedacit(0, 0, 0, 0, 0);
        }

        int enviados = 0;
        int pendentes = 0;
        int erros = 0;
        int ignorados = 0;
        log.info(
                "[RETENTATIVA] [VEDACIT][SFTP] itens={} | pausa={}s | somente timeout de leitura",
                registros.size(),
                intervaloEntreItensMs / 1000
        );
        for (int indice = 0; indice < registros.size(); indice++) {
            LogIntegracaoModel registro = registros.get(indice);
            long inicioItem = System.nanoTime();
            registrarOrigemSftpDoCanhoto(registro);
            ResultadoRegistro resultado = etlRegistroService.reprocessarCanhotoVedacitPorCte(registro);
            if (resultado == ResultadoRegistro.ENVIADO) {
                enviados++;
            } else if (resultado == ResultadoRegistro.PENDENTE_FOTO) {
                pendentes++;
            } else if (resultado.erro()) {
                erros++;
            } else {
                ignorados++;
            }
            logarProgressoSftp(
                    indice + 1, registros.size(), registro, resultado,
                    Duration.ofNanos(System.nanoTime() - inicioItem),
                    enviados, pendentes, erros, ignorados
            );
            if (indice < registros.size() - 1 && !pausarEntreRegistros(intervaloEntreItensMs)) {
                log.warn("[ATENCAO] [VEDACIT][SFTP] Retentativa controlada interrompida antes do próximo timeout.");
                break;
            }
        }
        return new ResultadoReprocessamentoCanhotoVedacit(
                enviados + pendentes + erros + ignorados,
                enviados,
                pendentes,
                erros,
                ignorados
        );
    }

    public long contarNfesCandidatasCanhotoVedacitSftp(List<String> chavesNfeComArquivoSftp) {
        if (chavesNfeComArquivoSftp == null || chavesNfeComArquivoSftp.isEmpty()) {
            return 0;
        }
        return logIntegracaoRepository.countNfesCandidatasCanhotoVedacitPorNfes(chavesNfeComArquivoSftp);
    }

    private void logarProgressoSftp(
            int itemAtual,
            int totalItens,
            LogIntegracaoModel registro,
            ResultadoRegistro resultado,
            Duration duracao,
            int enviados,
            int pendentes,
            int erros,
            int ignorados
    ) {
        String motivo = resultado.erro()
                ? " | motivo=" + resumirMensagem(registro.getMensagemErroCanhoto())
                : "";
        log.info(
                "{} [VEDACIT][SFTP] {}/{} | {} | NF={} | duracao={} | acumulado enviados={} erros={} pendentes={} ignorados={}{}",
                simboloResultado(resultado), itemAtual, totalItens, resultado.name(), chaveResumida(registro.getChaveNfe()),
                formatarDuracao(duracao), enviados, erros, pendentes, ignorados, motivo
        );
        logDetalheSftpVedacit.info(
                "[VEDACIT][SFTP][DETALHE] item={}/{} resultado={} nfe={} cte_original={} cte_efetivo={} duracao_ms={}",
                itemAtual, totalItens, resultado.name(), registro.getChaveNfe(), registro.getChaveCte(),
                registro.getCanhotoChaveCteEfetiva(), duracao.toMillis()
        );
    }

    private String simboloResultado(ResultadoRegistro resultado) {
        if (resultado == ResultadoRegistro.ENVIADO) return "[OK]";
        if (resultado.erro()) return "[ERRO]";
        if (resultado == ResultadoRegistro.IGNORADO || resultado == ResultadoRegistro.JA_PROCESSADO) return "[PULAR]";
        return "[PENDENTE]";
    }

    private String chaveResumida(String chave) {
        if (chave == null || chave.length() <= 12) return String.valueOf(chave);
        return chave.substring(0, 6) + "..." + chave.substring(chave.length() - 6);
    }

    private String resumirMensagem(String mensagem) {
        if (mensagem == null || mensagem.isBlank()) return "erro sem detalhe";
        String resumo = mensagem.replaceAll("\\s+", " ").trim();
        return resumo.length() <= 120 ? resumo : resumo.substring(0, 117) + "...";
    }

    private String formatarDuracao(Duration duracao) {
        long totalSegundos = Math.max(0, duracao.toSeconds());
        return totalSegundos >= 60
                ? "%dm%02ds".formatted(totalSegundos / 60, totalSegundos % 60)
                : "%ds".formatted(totalSegundos);
    }

    /**
     * Repescagem noturna limitada a falhas tecnicas Vedacit. Recusas de negocio
     * e CT-es sem chave ficam fora da selecao e continuam visiveis na quarentena.
     */
    public ResultadoRepescagemNoturnaVedacit reprocessarPendenciasTecnicasVedacit(
            int limiteItens,
            int limiteTentativas
    ) {
        int limiteSeguro = Math.max(1, Math.min(limiteItens, 500));
        int tentativasSeguras = Math.max(1, limiteTentativas);
        List<LogIntegracaoModel> dados = new ArrayList<>(normalizarLista(
                logIntegracaoRepository.findCandidatosRepescagemNoturnaVedacitDados(
                        tentativasSeguras,
                        PageRequest.of(0, limiteSeguro)
                )
        ));
        int restanteParaHistoricos = Math.max(0, limiteSeguro - dados.size());
        List<LogIntegracaoModel> dadosHistoricos = buscarDadosHistoricosTecnicosVedacit(
                restanteParaHistoricos,
                tentativasSeguras
        );
        dados.addAll(dadosHistoricos);
        int restante = Math.max(0, limiteSeguro - dados.size());
        List<LogIntegracaoModel> canhotos = restante == 0
                ? List.of()
                : normalizarLista(logIntegracaoRepository.findCandidatosRepescagemNoturnaVedacitCanhoto(
                        tentativasSeguras,
                        PageRequest.of(0, restante)
                ));

        if (dados.isEmpty() && canhotos.isEmpty()) {
            log.info("🌙 [VEDACIT] Repescagem noturna: nenhuma falha técnica elegível encontrada.");
            return new ResultadoRepescagemNoturnaVedacit(0, 0, 0, 0, 0);
        }

        int enviados = 0;
        int pendentes = 0;
        int erros = 0;
        List<LogIntegracaoModel> registros = new ArrayList<>(dados.size() + canhotos.size());
        registros.addAll(dados);
        registros.addAll(canhotos);

        log.warn(
                "🌙 [VEDACIT] Iniciando repescagem noturna registrada: xml={} (historicos={}) canhotos={} limite_tentativas={}.",
                dados.size(), dadosHistoricos.size(), canhotos.size(), tentativasSeguras
        );
        for (int indice = 0; indice < registros.size(); indice++) {
            LogIntegracaoModel registro = registros.get(indice);
            ResultadoRegistro resultado = indice < dados.size()
                    ? etlRegistroService.reprocessarXmlCteVedacitPorChave(registro)
                    : etlRegistroService.reprocessarCanhotoVedacitPorCte(registro);
            if (resultado == ResultadoRegistro.ENVIADO) {
                enviados++;
            } else if (resultado == ResultadoRegistro.PENDENTE_FOTO) {
                pendentes++;
            } else if (resultado.erro()) {
                erros++;
            }

            log.info("🌙 [VEDACIT] NF {}: repescagem noturna resultado={}", registro.getChaveNfe(), resultado);
            if (indice < registros.size() - 1 && !pausarEntreRegistros()) {
                log.warn("⏹️ [VEDACIT] Repescagem noturna interrompida antes de concluir os candidatos.");
                break;
            }
        }

        ResultadoRepescagemNoturnaVedacit resultado = new ResultadoRepescagemNoturnaVedacit(
                dados.size(), canhotos.size(), enviados, pendentes, erros
        );
        log.warn(
                "🌙 [VEDACIT] Repescagem noturna finalizada: selecionados_xml={} selecionados_canhoto={} enviados={} pendentes={} erros={}.",
                resultado.selecionadosXml(), resultado.selecionadosCanhoto(), resultado.enviados(),
                resultado.pendentes(), resultado.erros()
        );
        return resultado;
    }

    public record ResultadoReprocessamentoCanhotoVedacit(
            int selecionados,
            int enviados,
            int pendentes,
            int erros,
            int ignorados
    ) {
        public boolean concluidoSemErro() {
            return erros == 0;
        }
    }

    public record ResultadoRepescagemNoturnaVedacit(
            int selecionadosXml,
            int selecionadosCanhoto,
            int enviados,
            int pendentes,
            int erros
    ) {
        public boolean concluidoSemErro() {
            return erros == 0;
        }
    }

    private List<LogIntegracaoModel> buscarErrosDefinitivosDoCiclo(LocalDateTime inicioCiclo) {
        if (inicioCiclo == null) {
            log.warn("⏭️ Repescagem de erros definitivos do ciclo ignorada: início do ciclo não informado.");
            return List.of();
        }

        List<LogIntegracaoModel> registros = logIntegracaoRepository.findErrosManuaisDesde(inicioCiclo);
        return registros != null ? registros : List.of();
    }

    private List<LogIntegracaoModel> normalizarLista(List<LogIntegracaoModel> registros) {
        return registros != null ? registros : List.of();
    }

    /**
     * Recupera somente erros técnicos históricos em que a chave do CT-e ficou
     * registrada no URL da falha 401, mas não chegou a ser persistida no campo
     * próprio da auditoria. A chave é validada pelo formato antes do reenvio e
     * passa a compor o mesmo log auditável da tentativa original.
     */
    private List<LogIntegracaoModel> buscarDadosHistoricosTecnicosVedacit(
            int limite,
            int limiteTentativas
    ) {
        if (limite <= 0) {
            return List.of();
        }

        List<LogIntegracaoModel> candidatos = normalizarLista(
                logIntegracaoRepository.findQuarentenaByDestino(DESTINO_VEDACIT)
        );
        List<LogIntegracaoModel> selecionados = new ArrayList<>();
        for (LogIntegracaoModel candidato : candidatos) {
            if (!ehCandidatoHistoricoTecnicoVedacit(candidato, limiteTentativas)) {
                continue;
            }

            Optional<String> chaveCte = extrairChaveCteDaMensagem(candidato);
            if (chaveCte.isEmpty()) {
                continue;
            }

            candidato.setChaveCte(chaveCte.get());
            selecionados.add(candidato);
            if (selecionados.size() >= limite) {
                break;
            }
        }
        return selecionados;
    }

    private boolean ehCandidatoHistoricoTecnicoVedacit(
            LogIntegracaoModel registro,
            int limiteTentativas
    ) {
        return registro != null
                && DESTINO_VEDACIT.equals(registro.getSistemaDestino())
                && STATUS_ERRO_DESTINO.equals(registro.getStatus())
                && STATUS_ERRO_DESTINO.equals(registro.getStatusDados())
                && registro.getChaveNfe() != null
                && registro.getChaveNfe().length() == 44
                && (registro.getChaveCte() == null || registro.getChaveCte().isBlank())
                && valorTentativas(registro.getTentativasDados()) < limiteTentativas
                && mensagemTecnica(registro).toLowerCase(Locale.ROOT).contains("401 unauthorized");
    }

    private Optional<String> extrairChaveCteDaMensagem(LogIntegracaoModel registro) {
        Matcher matcher = CHAVE_CTE_NA_MENSAGEM.matcher(mensagemTecnica(registro));
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private String mensagemTecnica(LogIntegracaoModel registro) {
        if (registro == null) {
            return "";
        }
        if (registro.getMensagemErroDados() != null && !registro.getMensagemErroDados().isBlank()) {
            return registro.getMensagemErroDados();
        }
        if (registro.getMensagemErroCanhoto() != null && !registro.getMensagemErroCanhoto().isBlank()) {
            return registro.getMensagemErroCanhoto();
        }
        return registro.getErro() != null ? registro.getErro() : "";
    }

    private List<LogIntegracaoModel> buscarErrosParciaisCanhotoPendentesRetry() {
        List<LogIntegracaoModel> registros = logIntegracaoRepository.findErrosParciaisCanhotoPendentesRetry();
        return registros != null ? registros : List.of();
    }

    private void reprocessarRegistro(LogIntegracaoModel registro) {
        String destino = normalizarDestino(registro);
        if (destino == null) {
            log.warn(
                    "⏭️ Repescagem ignorou log sem destino válido. id={} nf={}",
                    registro.getId(),
                    registro.getChaveNfe()
            );
            return;
        }
        if (DESTINO_SELIA.equals(destino)) {
            log.warn(
                    "⏭️ [SELIA] NF {}: repescagem genérica bloqueada; o reprocessamento exige fluxo SELIA específico.",
                    registro.getChaveNfe()
            );
            return;
        }

        log.warn(
                "🎣 [{}] NF {}: iniciando repescagem. tentativas_dados={} tentativas_canhoto={}",
                destino,
                registro.getChaveNfe(),
                valorTentativas(registro.getTentativasDados()),
                valorTentativas(registro.getTentativasCanhoto())
        );

        try {
            ResultadoRegistro resultado = etlRegistroService.reprocessarLogExistente(
                    destino,
                    headerAuth(destino),
                    registro,
                    processadorDestino(destino)
            );
            log.warn("🎣 [{}] NF {}: resultado da repescagem={}", destino, registro.getChaveNfe(), resultado);
        } catch (Exception e) {
            log.error(
                    "❌ [{}] NF {}: falha inesperada na repescagem - {}",
                    destino,
                    registro.getChaveNfe(),
                    e.getMessage(),
                    e
            );
        }
    }

    private ProcessadorDestino processadorDestino(String destino) {
        if (DESTINO_PPG.equals(destino)) {
            return (ocorrencia, comprovante, logIntegracao) ->
                    ppgIntegrationService.processarOcorrencia(ocorrencia, comprovante);
        }

        if (DESTINO_SELIA.equals(destino)) {
            return (ocorrencia, comprovante, logIntegracao) ->
                    seliaIntegrationService.processarOcorrencia(ocorrencia, comprovante);
        }

        return (ocorrencia, comprovante, logIntegracao) -> vedacitIntegrationService.processarOcorrencia(
                ocorrencia,
                comprovante,
                etlEstadoIntegracaoService.statusSucesso(logIntegracao.getStatusDados()),
                etlEstadoIntegracaoService.statusSucesso(logIntegracao.getStatusCanhoto())
        );
    }

    private String headerAuth(String destino) {
        if (DESTINO_PPG.equals(destino)) {
            return "Bearer " + tokenPpgEsl;
        }

        return "Bearer " + (DESTINO_SELIA.equals(destino) ? tokenSeliaEsl : tokenVedacitEsl);
    }

    private String normalizarDestino(LogIntegracaoModel registro) {
        if (registro == null || registro.getSistemaDestino() == null) {
            return null;
        }

        String destino = registro.getSistemaDestino().trim().toUpperCase(Locale.ROOT);
        if (DESTINO_PPG.equals(destino) || DESTINO_SELIA.equals(destino) || DESTINO_VEDACIT.equals(destino)) {
            return destino;
        }

        return null;
    }

    private boolean pausarEntreRegistros() {
        return pausarEntreRegistros(intervaloEntreRegistrosMs);
    }

    private boolean pausarEntreRegistros(long intervaloMs) {
        long esperaMs = Math.max(0, intervaloMs);
        if (esperaMs <= 0) {
            return true;
        }

        try {
            Thread.sleep(esperaMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private int valorTentativas(Integer tentativas) {
        return tentativas != null ? tentativas : 0;
    }
}
