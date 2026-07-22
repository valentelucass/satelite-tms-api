package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.selia.SeliaIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

@Service
public class OrquestradorEtlService {

    private static final Logger log = LoggerFactory.getLogger(OrquestradorEtlService.class);

    public static final int CODIGO_SAIDA_SUCESSO = 0;
    public static final int CODIGO_SAIDA_ERRO_CRITICO = 1;

    private static final String LINHA_BANNER = "==================================================";
    private static final String DESTINO_PPG = "PPG";
    private static final String DESTINO_SELIA = "SELIA";
    private static final String DESTINO_VEDACIT = "VEDACIT";

    private final PpgIntegrationService ppgIntegrationService;
    private final SeliaIntegrationService seliaIntegrationService;
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

    @Value("${APP_PPG_ENABLED:true}")
    private boolean ppgEnabled = true;

    @Value("${APP_VEDACIT_ENABLED:true}")
    private boolean vedacitEnabled = true;

    @Value("${APP_SELIA_ENABLED:false}")
    private boolean seliaEnabled;

    @Value("${INTEGRATION_MAX_PAGES_PER_CYCLE:10}")
    private int maxPaginasPorCiclo;

    @Value("${APP_CICLO_UNICO:${ciclo_unico:false}}")
    private boolean cicloUnico;

    @Autowired
    public OrquestradorEtlService(
            PpgIntegrationService ppgIntegrationService,
            SeliaIntegrationService seliaIntegrationService,
            VedacitIntegrationService vedacitIntegrationService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            EtlFluxoDestinoService etlFluxoDestinoService,
            QuarentenaService quarentenaService,
            EtlRepescagemService etlRepescagemService
    ) {
        this.ppgIntegrationService = ppgIntegrationService;
        this.seliaIntegrationService = seliaIntegrationService;
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
                resultadoVedacit = etlFluxoDestinoService.executarFluxoDestino(
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
                        tokenSeliaEsl,
                        execucao,
                        (ocorrencia, comprovante, logIntegracao) ->
                                seliaIntegrationService.processarOcorrencia(ocorrencia, comprovante)
                );
            } else {
                log.warn("⏸️ [DESTINO: {}] Fluxo desabilitado por APP_SELIA_ENABLED=false.", DESTINO_SELIA);
                resultadoSelia = ResultadoDestino.desabilitado(DESTINO_SELIA);
            }
        } finally {
            executarRepescagemComSeguranca(inicioCiclo);

            LocalDateTime fimCiclo = LocalDateTime.now();
            int recebidasTotal = resultadoPpg.recebidos() + resultadoSelia.recebidos() + resultadoVedacit.recebidos();
            int ignoradasTotal = resultadoPpg.ignorados() + resultadoSelia.ignorados() + resultadoVedacit.ignorados();
            int pendentesFotoTotal = resultadoPpg.pendentesFoto() + resultadoSelia.pendentesFoto() + resultadoVedacit.pendentesFoto();
            int jaProcessadasTotal = resultadoPpg.jaProcessados() + resultadoSelia.jaProcessados() + resultadoVedacit.jaProcessados();
            int sucessosTotal = resultadoPpg.enviados() + resultadoSelia.enviados() + resultadoVedacit.enviados();
            int errosTotal = resultadoPpg.erros() + resultadoSelia.erros() + resultadoVedacit.erros();
            boolean erroCritico = resultadoPpg.erroCritico() || resultadoSelia.erroCritico() || resultadoVedacit.erroCritico();
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

            logarRelatorioQuarentena();

            resultadoCiclo = new ResultadoCiclo(
                    resultadoPpg,
                    resultadoSelia,
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

    private void logarRelatorioQuarentena() {
        try {
            List<LogIntegracaoModel> quarentenaPpg = buscarQuarentena(DESTINO_PPG);
            List<LogIntegracaoModel> quarentenaSelia = buscarQuarentena(DESTINO_SELIA);
            List<LogIntegracaoModel> quarentenaVedacit = buscarQuarentena(DESTINO_VEDACIT);
            if (quarentenaPpg.isEmpty() && quarentenaSelia.isEmpty() && quarentenaVedacit.isEmpty()) {
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
