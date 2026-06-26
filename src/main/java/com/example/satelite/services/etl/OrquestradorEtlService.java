package com.example.satelite.services.etl;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

@Service
public class OrquestradorEtlService {

    private static final Logger log = LoggerFactory.getLogger(OrquestradorEtlService.class);

    public static final int CODIGO_SAIDA_SUCESSO = 0;
    public static final int CODIGO_SAIDA_ERRO_CRITICO = 1;

    private static final String LINHA_BANNER = "==================================================";
    private static final String DESTINO_PPG = "PPG";
    private static final String DESTINO_VEDACIT = "VEDACIT";

    private final PpgIntegrationService ppgIntegrationService;
    private final VedacitIntegrationService vedacitIntegrationService;
    private final EtlEstadoIntegracaoService etlEstadoIntegracaoService;
    private final EtlFluxoDestinoService etlFluxoDestinoService;

    @Value("${RODOGARCIA_TOKEN_PPG}")
    private String tokenPpgEsl;

    @Value("${RODOGARCIA_TOKEN_VEDACIT}")
    private String tokenVedacitEsl;

    @Value("${APP_PPG_ENABLED:true}")
    private boolean ppgEnabled = true;

    @Value("${APP_VEDACIT_ENABLED:true}")
    private boolean vedacitEnabled = true;

    @Value("${INTEGRATION_MAX_PAGES_PER_CYCLE:10}")
    private int maxPaginasPorCiclo;

    @Value("${APP_CICLO_UNICO:${ciclo_unico:false}}")
    private boolean cicloUnico;

    public OrquestradorEtlService(
            PpgIntegrationService ppgIntegrationService,
            VedacitIntegrationService vedacitIntegrationService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            EtlFluxoDestinoService etlFluxoDestinoService
    ) {
        this.ppgIntegrationService = ppgIntegrationService;
        this.vedacitIntegrationService = vedacitIntegrationService;
        this.etlEstadoIntegracaoService = etlEstadoIntegracaoService;
        this.etlFluxoDestinoService = etlFluxoDestinoService;
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
        } finally {
            LocalDateTime fimCiclo = LocalDateTime.now();
            int recebidasTotal = resultadoPpg.recebidos() + resultadoVedacit.recebidos();
            int ignoradasTotal = resultadoPpg.ignorados() + resultadoVedacit.ignorados();
            int pendentesFotoTotal = resultadoPpg.pendentesFoto() + resultadoVedacit.pendentesFoto();
            int jaProcessadasTotal = resultadoPpg.jaProcessados() + resultadoVedacit.jaProcessados();
            int sucessosTotal = resultadoPpg.enviados() + resultadoVedacit.enviados();
            int errosTotal = resultadoPpg.erros() + resultadoVedacit.erros();
            boolean erroCritico = resultadoPpg.erroCritico() || resultadoVedacit.erroCritico();
            String resultadoFinal = erroCritico || errosTotal > 0 ? "CONCLUIDO_COM_ERROS" : "CONCLUIDO_SEM_ERROS";
            int codigoSaida = erroCritico ? CODIGO_SAIDA_ERRO_CRITICO : CODIGO_SAIDA_SUCESSO;
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

            resultadoCiclo = new ResultadoCiclo(
                    resultadoPpg,
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

    private void logarBannerInicio(LocalDateTime inicioCiclo, ExecucaoEtlRequest request) {
        log.info("""

                {}
                🚀 INICIANDO CICLO DE INTEGRAÇÃO ETL
                {}
                🕒 Início: {}
                🧭 Modo: {}
                📅 Janela retroativa: {} até {}
                📄 Máximo de páginas por destino: {}
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
            ResultadoDestino resultadoVedacit,
            boolean erroCritico,
            int codigoSaida,
            String resultadoFinal,
            LocalDateTime inicio,
            LocalDateTime fim
    ) {
    }
}
