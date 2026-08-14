package com.example.satelite.services.etl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.selia.SeliaIntegrationService;
import com.example.satelite.services.supporte.SupporteIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

@Service
public class OrquestradorEtlService {

    private static final Logger log = LoggerFactory.getLogger(OrquestradorEtlService.class);

    public static final int CODIGO_SAIDA_SUCESSO = 0;
    public static final int CODIGO_SAIDA_ERRO_CRITICO = 1;

    private static final String LINHA_BANNER = "==================================================";
    private static final String DESTINO_PPG = "PPG";
    private static final String DESTINO_SELIA = "SELIA";
    private static final String DESTINO_SUPPORTE = "SUPPORTE";
    private static final String DESTINO_VEDACIT = "VEDACIT";

    private final PpgIntegrationService ppgIntegrationService;
    private final SeliaIntegrationService seliaIntegrationService;
    private final SupporteIntegrationService supporteIntegrationService;
    private final VedacitIntegrationService vedacitIntegrationService;
    private final EtlEstadoIntegracaoService etlEstadoIntegracaoService;
    private final EtlFluxoDestinoService etlFluxoDestinoService;
    private final QuarentenaService quarentenaService;
    private final EtlRepescagemService etlRepescagemService;

    @Value("${RODOGARCIA_TOKEN_PPG}")
    private String tokenPpgEsl;

    @Value("${RODOGARCIA_TOKEN_VEDACIT}")
    private String tokenVedacitEsl;

    @Value("${RODOGARCIA_TOKEN_SELIA:}")
    private String tokenSeliaEsl;

    @Value("${RODOGARCIA_TOKEN_SUPPORTE:}")
    private String tokenSupporteEsl;

    @Value("${APP_PPG_ENABLED:true}")
    private boolean ppgEnabled = true;

    @Value("${APP_VEDACIT_ENABLED:true}")
    private boolean vedacitEnabled = true;

    @Value("${APP_SELIA_ENABLED:false}")
    private boolean seliaEnabled;

    @Value("${APP_SUPPORTE_ENABLED:false}")
    private boolean supporteEnabled;

    @Value("${INTEGRATION_MAX_PAGES_PER_CYCLE:10}")
    private int maxPaginasPorCiclo;

    @Value("${APP_CICLO_UNICO:${ciclo_unico:false}}")
    private boolean cicloUnico;

    @Value("${APP_ETL_REPESCAGEM_ENABLED:true}")
    private boolean repescagemEnabled = true;

    @Value("${VEDACIT_XML_BACKFILL_ENABLED:false}")
    private boolean vedacitXmlBackfillEnabled;

    @Value("${VEDACIT_XML_BACKFILL_START_DATE:2026-05-01}")
    private String vedacitXmlBackfillStartDate;

    @Autowired
    public OrquestradorEtlService(
            PpgIntegrationService ppgIntegrationService,
            SeliaIntegrationService seliaIntegrationService,
            SupporteIntegrationService supporteIntegrationService,
            VedacitIntegrationService vedacitIntegrationService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            EtlFluxoDestinoService etlFluxoDestinoService,
            QuarentenaService quarentenaService,
            EtlRepescagemService etlRepescagemService
    ) {
        this.ppgIntegrationService = ppgIntegrationService;
        this.seliaIntegrationService = seliaIntegrationService;
        this.supporteIntegrationService = supporteIntegrationService;
        this.vedacitIntegrationService = vedacitIntegrationService;
        this.etlEstadoIntegracaoService = etlEstadoIntegracaoService;
        this.etlFluxoDestinoService = etlFluxoDestinoService;
        this.quarentenaService = quarentenaService;
        this.etlRepescagemService = etlRepescagemService;
    }

    public OrquestradorEtlService(
            PpgIntegrationService ppgIntegrationService,
            VedacitIntegrationService vedacitIntegrationService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            EtlFluxoDestinoService etlFluxoDestinoService,
            QuarentenaService quarentenaService,
            EtlRepescagemService etlRepescagemService
    ) {
        this(
                ppgIntegrationService,
                null,
                null,
                vedacitIntegrationService,
                etlEstadoIntegracaoService,
                etlFluxoDestinoService,
                quarentenaService,
                etlRepescagemService
        );
    }

    public void executarFluxos() {
        executarFluxosComResultado();
    }

    public ResultadoCiclo executarFluxosComResultado() {
        return executarFluxosComResultado(ExecucaoEtlRequest.incremental(maxPaginasPorCiclo));
    }

    public ResultadoCiclo executarFluxosComResultado(ExecucaoEtlRequest request) {
        ExecucaoEtlRequest execucao = request != null
                ? request
                : ExecucaoEtlRequest.incremental(maxPaginasPorCiclo);
        LocalDateTime inicioCiclo = LocalDateTime.now();
        ResultadoDestino resultadoPpg = ResultadoDestino.vazio(DESTINO_PPG);
        ResultadoDestino resultadoSelia = ResultadoDestino.vazio(DESTINO_SELIA);
        ResultadoDestino resultadoSupporte = ResultadoDestino.vazio(DESTINO_SUPPORTE);
        ResultadoDestino resultadoVedacit = ResultadoDestino.vazio(DESTINO_VEDACIT);
        ResultadoCiclo resultadoCiclo;

        logarBannerInicio(inicioCiclo, execucao);

        try {
            if (!execucao.destinoSelecionado(DESTINO_PPG)) {
                log.warn("⏸️ [DESTINO: {}] Fluxo não selecionado para esta execução.", DESTINO_PPG);
                resultadoPpg = ResultadoDestino.naoSelecionado(DESTINO_PPG);
            } else if (ppgEnabled) {
                resultadoPpg = etlFluxoDestinoService.executarFluxoDestino(
                        DESTINO_PPG,
                        tokenPpgEsl,
                        execucao,
                        (ocorrencia, comprovante, logIntegracao) ->
                                ppgIntegrationService.processarOcorrencia(ocorrencia, comprovante)
                );
            } else {
                log.warn("⏸️ [DESTINO: {}] Fluxo desabilitado por APP_PPG_ENABLED=false.", DESTINO_PPG);
                resultadoPpg = ResultadoDestino.desabilitado(DESTINO_PPG);
            }

            if (!execucao.destinoSelecionado(DESTINO_VEDACIT)) {
                log.warn("⏸️ [DESTINO: {}] Fluxo não selecionado para esta execução.", DESTINO_VEDACIT);
                resultadoVedacit = ResultadoDestino.naoSelecionado(DESTINO_VEDACIT);
            } else if (vedacitEnabled) {
                ExecucaoEtlRequest execucaoXmlVedacit = obterExecucaoXmlVedacit(execucao);
                ResultadoDestino resultadoVedacitXml = etlFluxoDestinoService.executarFluxoDestino(
                        DESTINO_VEDACIT,
                        "VEDACIT_XML",
                        tokenVedacitEsl,
                        execucaoXmlVedacit,
                        EtapaVedacit.EMISSAO_XML.codigoOcorrencia(),
                        false,
                        (ocorrencia, comprovante, logIntegracao) -> ResultadoIntegracao.ignorado()
                );
                ResultadoDestino resultadoVedacitCanhoto = etlFluxoDestinoService.executarFluxoDestino(
                        DESTINO_VEDACIT,
                        tokenVedacitEsl,
                        execucao,
                        (ocorrencia, comprovante, logIntegracao) -> vedacitIntegrationService.processarOcorrencia(
                                ocorrencia,
                                comprovante,
                                etlEstadoIntegracaoService.statusSucesso(logIntegracao.getStatusDados()),
                                etlEstadoIntegracaoService.statusSucesso(logIntegracao.getStatusCanhoto())
                        )
                );
                resultadoVedacit = resultadoVedacitXml.combinar(
                        resultadoVedacitCanhoto,
                        "XML por emissao e canhoto por entrega concluídos"
                );
            } else {
                log.warn("⏸️ [DESTINO: {}] Fluxo desabilitado por APP_VEDACIT_ENABLED=false.", DESTINO_VEDACIT);
                resultadoVedacit = ResultadoDestino.desabilitado(DESTINO_VEDACIT);
            }

            if (!execucao.destinoSelecionado(DESTINO_SELIA)) {
                log.warn("⏸️ [DESTINO: {}] Fluxo não selecionado para esta execução.", DESTINO_SELIA);
                resultadoSelia = ResultadoDestino.naoSelecionado(DESTINO_SELIA);
            } else if (seliaEnabled && seliaIntegrationService != null) {
                resultadoSelia = etlFluxoDestinoService.executarFluxoDestino(
                        DESTINO_SELIA,
                        DESTINO_SELIA,
                        tokenSeliaEsl,
                        execucao,
                        seliaIntegrationService.filtroCodigoOcorrencia(),
                        true,
                        (ocorrencia, comprovante, logIntegracao) ->
                                seliaIntegrationService.processarOcorrencia(ocorrencia, comprovante)
                );
            } else {
                log.warn("⏸️ [DESTINO: {}] Fluxo desabilitado por APP_SELIA_ENABLED=false.", DESTINO_SELIA);
                resultadoSelia = ResultadoDestino.desabilitado(DESTINO_SELIA);
            }

            if (!execucao.destinoSelecionado(DESTINO_SUPPORTE)) {
                log.warn("⏸️ [DESTINO: {}] Fluxo não selecionado para esta execução.", DESTINO_SUPPORTE);
                resultadoSupporte = ResultadoDestino.naoSelecionado(DESTINO_SUPPORTE);
            } else if (supporteEnabled && supporteIntegrationService != null && tokenSupporteEsl != null
                    && !tokenSupporteEsl.isBlank()) {
                resultadoSupporte = etlFluxoDestinoService.executarFluxoDestino(
                        DESTINO_SUPPORTE,
                        tokenSupporteEsl,
                        execucao,
                        (ocorrencia, comprovante, logIntegracao) ->
                                supporteIntegrationService.processarOcorrencia(ocorrencia, comprovante)
                );
            } else {
                log.warn("⏸️ [DESTINO: {}] Fluxo desabilitado ou sem token ESL SUPPORTE.", DESTINO_SUPPORTE);
                resultadoSupporte = ResultadoDestino.desabilitado(DESTINO_SUPPORTE);
            }
        } finally {
            if (repescagemEnabled) {
                executarRepescagemComSeguranca(inicioCiclo);
            } else {
                log.info("⏭️ Repescagem desabilitada para este ciclo por APP_ETL_REPESCAGEM_ENABLED=false.");
            }

            LocalDateTime fimCiclo = LocalDateTime.now();
            int recebidasTotal = resultadoPpg.recebidos() + resultadoSelia.recebidos() + resultadoSupporte.recebidos() + resultadoVedacit.recebidos();
            int ignoradasTotal = resultadoPpg.ignorados() + resultadoSelia.ignorados() + resultadoSupporte.ignorados() + resultadoVedacit.ignorados();
            int pendentesFotoTotal = resultadoPpg.pendentesFoto() + resultadoSelia.pendentesFoto() + resultadoSupporte.pendentesFoto() + resultadoVedacit.pendentesFoto();
            int jaProcessadasTotal = resultadoPpg.jaProcessados() + resultadoSelia.jaProcessados() + resultadoSupporte.jaProcessados() + resultadoVedacit.jaProcessados();
            int sucessosTotal = resultadoPpg.enviados() + resultadoSelia.enviados() + resultadoSupporte.enviados() + resultadoVedacit.enviados();
            int errosTotal = resultadoPpg.erros() + resultadoSelia.erros() + resultadoSupporte.erros() + resultadoVedacit.erros();
            boolean erroCritico = resultadoPpg.erroCritico() || resultadoSelia.erroCritico() || resultadoSupporte.erroCritico() || resultadoVedacit.erroCritico();
            String resultadoFinal = erroCritico || errosTotal > 0 ? "CONCLUIDO_COM_ERROS" : "CONCLUIDO_SEM_ERROS";
            int codigoSaida = erroCritico || errosTotal > 0
                    ? CODIGO_SAIDA_ERRO_CRITICO
                    : CODIGO_SAIDA_SUCESSO;
            String proximoPasso = execucao.retroativo()
                    ? "🏁 Carga retroativa concluída; aplicação será encerrada."
                    : cicloUnico
                    ? "🏁 Ciclo único concluído; aplicação será encerrada."
                    : "🕐 Aguardando próximo ciclo...";

            log.info("""

                    {}
                    📊 RESUMO GERAL DO CICLO ETL
                    {}
                    🕒 Início: {}
                    🕓 Fim: {}
                    🧭 Resultado: {}
                    📦 Total de Notas Lidas: {}
                    ⏭️  Ignoradas (Outro Status): {}
                    ⏳ Pendentes de Foto: {}
                    ♻️  Já Processadas: {}
                    ✅ Enviadas com Sucesso: {}
                    ❌ Falhas: {}
                    💥 Erro Crítico: {}
                    🚦 Código de Saída Sugerido: {}
                    --------------------------------------------------
                    PPG     | páginas={} | lidas={} | ignoradas={} | pend_foto={} | já_processadas={} | sucessos={} | falhas={} | encerramento={}
                    SELIA   | páginas={} | lidas={} | ignoradas={} | pend_foto={} | já_processadas={} | sucessos={} | falhas={} | encerramento={}
                    SUPPORTE| páginas={} | lidas={} | ignoradas={} | pend_foto={} | já_processadas={} | sucessos={} | falhas={} | encerramento={}
                    VEDACIT | páginas={} | lidas={} | ignoradas={} | pend_foto={} | já_processadas={} | sucessos={} | falhas={} | encerramento={}
                    {}
                    {}
                    """,
                    LINHA_BANNER,
                    LINHA_BANNER,
                    inicioCiclo,
                    fimCiclo,
                    resultadoFinal,
                    recebidasTotal,
                    ignoradasTotal,
                    pendentesFotoTotal,
                    jaProcessadasTotal,
                    sucessosTotal,
                    errosTotal,
                    erroCritico,
                    codigoSaida,
                    resultadoPpg.paginasProcessadas(),
                    resultadoPpg.recebidos(),
                    resultadoPpg.ignorados(),
                    resultadoPpg.pendentesFoto(),
                    resultadoPpg.jaProcessados(),
                    resultadoPpg.enviados(),
                    resultadoPpg.erros(),
                    resultadoPpg.mensagemEncerramento(),
                    resultadoSelia.paginasProcessadas(),
                    resultadoSelia.recebidos(),
                    resultadoSelia.ignorados(),
                    resultadoSelia.pendentesFoto(),
                    resultadoSelia.jaProcessados(),
                    resultadoSelia.enviados(),
                    resultadoSelia.erros(),
                    resultadoSelia.mensagemEncerramento(),
                    resultadoSupporte.paginasProcessadas(),
                    resultadoSupporte.recebidos(),
                    resultadoSupporte.ignorados(),
                    resultadoSupporte.pendentesFoto(),
                    resultadoSupporte.jaProcessados(),
                    resultadoSupporte.enviados(),
                    resultadoSupporte.erros(),
                    resultadoSupporte.mensagemEncerramento(),
                    resultadoVedacit.paginasProcessadas(),
                    resultadoVedacit.recebidos(),
                    resultadoVedacit.ignorados(),
                    resultadoVedacit.pendentesFoto(),
                    resultadoVedacit.jaProcessados(),
                    resultadoVedacit.enviados(),
                    resultadoVedacit.erros(),
                    resultadoVedacit.mensagemEncerramento(),
                    LINHA_BANNER,
                    proximoPasso
            );

            logarRelatorioQuarentena(destinosAtivosNoCiclo(execucao));

            resultadoCiclo = new ResultadoCiclo(
                    resultadoPpg,
                    resultadoSelia,
                    resultadoSupporte,
                    resultadoVedacit,
                    erroCritico,
                    codigoSaida,
                    resultadoFinal,
                    inicioCiclo,
                    fimCiclo
            );
        }

        return resultadoCiclo;
    }

    private void executarRepescagemComSeguranca(LocalDateTime inicioCiclo) {
        try {
            etlRepescagemService.executarRepescagem(inicioCiclo);
        } catch (Exception e) {
            log.warn("⚠️ Repescagem final do ciclo falhou antes do relatório: {}", e.getMessage(), e);
        }
    }

    private ExecucaoEtlRequest obterExecucaoXmlVedacit(ExecucaoEtlRequest execucaoPadrao) {
        if (!vedacitXmlBackfillEnabled || execucaoPadrao.retroativo()) {
            return execucaoPadrao;
        }

        LocalDate inicio = LocalDate.parse(vedacitXmlBackfillStartDate);
        LocalDate fim = LocalDate.now();
        log.info("📚 [VEDACIT] Recuperação de XML habilitada: {} até {}.", inicio, fim);
        return ExecucaoEtlRequest.incrementalDesde(inicio, DESTINO_VEDACIT, maxPaginasPorCiclo);
    }

    private List<String> destinosAtivosNoCiclo(ExecucaoEtlRequest execucao) {
        List<String> destinos = new ArrayList<>();
        if (execucao.destinoSelecionado(DESTINO_PPG) && ppgEnabled) {
            destinos.add(DESTINO_PPG);
        }
        if (execucao.destinoSelecionado(DESTINO_SELIA) && seliaEnabled && seliaIntegrationService != null) {
            destinos.add(DESTINO_SELIA);
        }
        if (execucao.destinoSelecionado(DESTINO_SUPPORTE) && supporteEnabled
                && supporteIntegrationService != null && tokenSupporteEsl != null && !tokenSupporteEsl.isBlank()) {
            destinos.add(DESTINO_SUPPORTE);
        }
        if (execucao.destinoSelecionado(DESTINO_VEDACIT) && vedacitEnabled) {
            destinos.add(DESTINO_VEDACIT);
        }
        return destinos;
    }

    private void logarRelatorioQuarentena(List<String> destinosAtivos) {
        if (destinosAtivos.isEmpty()) {
            return;
        }

        try {
            List<LogIntegracaoModel> quarentenaPpg = destinosAtivos.contains(DESTINO_PPG)
                    ? buscarQuarentena(DESTINO_PPG) : List.of();
            List<LogIntegracaoModel> quarentenaSelia = destinosAtivos.contains(DESTINO_SELIA)
                    ? buscarQuarentena(DESTINO_SELIA) : List.of();
            List<LogIntegracaoModel> quarentenaSupporte = destinosAtivos.contains(DESTINO_SUPPORTE)
                    ? buscarQuarentena(DESTINO_SUPPORTE) : List.of();
            List<LogIntegracaoModel> quarentenaVedacit = destinosAtivos.contains(DESTINO_VEDACIT)
                    ? buscarQuarentena(DESTINO_VEDACIT) : List.of();
            if (quarentenaPpg.isEmpty() && quarentenaSelia.isEmpty() && quarentenaSupporte.isEmpty() && quarentenaVedacit.isEmpty()) {
                return;
            }

            String quebraLinha = System.lineSeparator();
            StringBuilder relatorio = new StringBuilder()
                    .append(quebraLinha)
                    .append(LINHA_BANNER)
                    .append(quebraLinha)
                    .append("📋 RELATÓRIO FINAL DE QUARENTENA - COPIAR PARA OPERAÇÃO")
                    .append(quebraLinha)
                    .append(LINHA_BANNER)
                    .append(quebraLinha);

            adicionarItensQuarentena(relatorio, DESTINO_PPG, quarentenaPpg);
            adicionarItensQuarentena(relatorio, DESTINO_SELIA, quarentenaSelia);
            adicionarItensQuarentena(relatorio, DESTINO_SUPPORTE, quarentenaSupporte);
            adicionarItensQuarentena(relatorio, DESTINO_VEDACIT, quarentenaVedacit);
            relatorio.append(LINHA_BANNER);

            log.warn("{}", relatorio);
        } catch (Exception e) {
            log.warn("⚠️ Não foi possível emitir o relatório de quarentena: {}", e.getMessage());
        }
    }

    private List<LogIntegracaoModel> buscarQuarentena(String destino) {
        List<LogIntegracaoModel> registros = quarentenaService.findQuarentenaByDestino(destino);
        return registros != null ? registros : List.of();
    }

    private void adicionarItensQuarentena(
            StringBuilder relatorio,
            String destino,
            List<LogIntegracaoModel> registros
    ) {
        if (registros.isEmpty()) {
            return;
        }

        for (LogIntegracaoModel registro : registros) {
            relatorio
                    .append("[")
                    .append(destino)
                    .append("] NF ")
                    .append(valorLog(registro.getChaveNfe()))
                    .append(" - ")
                    .append(valorLog(quarentenaService.erroLimpo(registro)))
                    .append(System.lineSeparator());
        }
    }

    private String valorLog(String valor) {
        return valor != null && !valor.isBlank() ? valor : "indisponivel";
    }

    private void logarBannerInicio(LocalDateTime inicioCiclo, ExecucaoEtlRequest request) {
        log.info("""

                {}
                🚀 INICIANDO CICLO DE INTEGRAÇÃO ETL
                {}
                🕒 Início: {}
                🧭 Modo: {}
                📅 Janela retroativa: {} até {}
                📄 Páginas por lote antes de pausa: {}
                {}
                """,
                LINHA_BANNER,
                LINHA_BANNER,
                inicioCiclo,
                request.modo(),
                request.dataInicial(),
                request.dataFinal(),
                request.maxPaginas(),
                LINHA_BANNER
        );
    }

    public record ResultadoCiclo(
            ResultadoDestino resultadoPpg,
            ResultadoDestino resultadoSelia,
            ResultadoDestino resultadoSupporte,
            ResultadoDestino resultadoVedacit,
            boolean erroCritico,
            int codigoSaida,
            String resultadoFinal,
            LocalDateTime inicio,
            LocalDateTime fim
    ) {
        public ResultadoCiclo(
                ResultadoDestino resultadoPpg,
                ResultadoDestino resultadoVedacit,
                boolean erroCritico,
                int codigoSaida,
                String resultadoFinal,
                LocalDateTime inicio,
                LocalDateTime fim
        ) {
            this(
                    resultadoPpg,
                    ResultadoDestino.vazio(DESTINO_SELIA),
                    ResultadoDestino.vazio(DESTINO_SUPPORTE),
                    resultadoVedacit,
                    erroCritico,
                    codigoSaida,
                    resultadoFinal,
                    inicio,
                    fim
            );
        }
    }
}
