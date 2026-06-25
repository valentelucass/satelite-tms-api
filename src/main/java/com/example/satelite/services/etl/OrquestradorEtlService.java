package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslItemDTO;
import com.example.satelite.dto.rodogarcia.EslLoteResponseDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.models.ControleCursor;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.ControleCursorRepository;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

@Service
public class OrquestradorEtlService {

    private static final Logger log = LoggerFactory.getLogger(OrquestradorEtlService.class);

    public static final int CODIGO_SAIDA_SUCESSO = 0;
    public static final int CODIGO_SAIDA_ERRO_CRITICO = 1;

    private static final int CODIGO_ENTREGA_REALIZADA = 1;
    private static final String LINHA_BANNER = "==================================================";
    private static final String URL_IMAGEM_TESTE_PADRAO = "https://www.w3.org/People/mimasa/test/imgformat/img/w3c_home.jpg";
    private static final ZoneOffset OFFSET_SINCE_ESL = ZoneOffset.of("-03:00");
    private static final DateTimeFormatter ESL_SINCE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final String DESTINO_PPG = "PPG";
    private static final String DESTINO_VEDACIT = "VEDACIT";
    private static final String STATUS_RECEBIDO = ResultadoIntegracao.STATUS_RECEBIDO;
    private static final String STATUS_IGNORADO = ResultadoIntegracao.STATUS_IGNORADO;
    private static final String STATUS_ENVIADO = ResultadoIntegracao.STATUS_ENVIADO;
    private static final String STATUS_ERRO_DESTINO = ResultadoIntegracao.STATUS_ERRO_DESTINO;
    private static final String STATUS_PENDENTE_FOTO = ResultadoIntegracao.STATUS_PENDENTE_FOTO;
    private static final String STATUS_SUCESSO = ResultadoIntegracao.STATUS_SUCESSO;
    private static final Set<String> STATUS_FINALIZADOS_SEM_REENVIO = Set.of(STATUS_ENVIADO, STATUS_IGNORADO);

    private final RodogarciaClient rodogarciaClient;
    private final PpgIntegrationService ppgIntegrationService;
    private final VedacitIntegrationService vedacitIntegrationService;
    private final LogIntegracaoRepository logIntegracaoRepository;
    private final ControleCursorRepository controleCursorRepository;
    private final EslRequestPolicyService eslRequestPolicyService;

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

    @Value("${INTEGRATION_MAX_RETRY_ATTEMPTS:3}")
    private int maxTentativasReprocessamento = 3;

    @Value("${APP_E2E_IMAGE_TEST_MODE:false}")
    private boolean modoTesteE2eImagem;

    @Value("${APP_E2E_TEST_IMAGE_URL:" + URL_IMAGEM_TESTE_PADRAO + "}")
    private String urlImagemTesteE2e;

    @Value("${VEDACIT_NFE_WHITELIST_ENABLED:false}")
    private boolean vedacitNfeWhitelistEnabled;

    @Value("${VEDACIT_NFE_WHITELIST:}")
    private String vedacitNfeWhitelist;

    @Value("${app.ppg.nfe-whitelist-enabled:false}")
    private boolean ppgNfeWhitelistEnabled;

    @Value("${app.ppg.nfe-whitelist:}")
    private String ppgNfeWhitelist;

    @Value("${APP_CICLO_UNICO:${ciclo_unico:false}}")
    private boolean cicloUnico;

    public OrquestradorEtlService(
            RodogarciaClient rodogarciaClient,
            PpgIntegrationService ppgIntegrationService,
            VedacitIntegrationService vedacitIntegrationService,
            LogIntegracaoRepository logIntegracaoRepository,
            ControleCursorRepository controleCursorRepository,
            EslRequestPolicyService eslRequestPolicyService
    ) {
        this.rodogarciaClient = rodogarciaClient;
        this.ppgIntegrationService = ppgIntegrationService;
        this.vedacitIntegrationService = vedacitIntegrationService;
        this.logIntegracaoRepository = logIntegracaoRepository;
        this.controleCursorRepository = controleCursorRepository;
        this.eslRequestPolicyService = eslRequestPolicyService;
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
                resultadoPpg = executarFluxoDestino(
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
                resultadoVedacit = executarFluxoDestino(
                        DESTINO_VEDACIT,
                        tokenVedacitEsl,
                        execucao,
                        (ocorrencia, comprovante, logIntegracao) -> vedacitIntegrationService.processarOcorrencia(
                                ocorrencia,
                                comprovante,
                                statusSucesso(logIntegracao.getStatusDados()),
                                statusSucesso(logIntegracao.getStatusCanhoto())
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

    private ResultadoDestino executarFluxoDestino(
            String destino,
            String tokenEsl,
            ExecucaoEtlRequest request,
            ProcessadorDestino processadorDestino
    ) {
        ResultadoDestino resultadoDestino = ResultadoDestino.vazio(destino);
        log.info("🚀 [DESTINO: {}] Iniciando varredura de ocorrências...", destino);

        try {
            String headerAuth = "Bearer " + tokenEsl;
            Long cursorAtual = request.buscarCursorInicial() ? buscarUltimoCursor(destino) : null;
            if (request.processarPendencias()) {
                ResultadoPagina resultadoPendencias = processarPendenciasDestino(destino, headerAuth, processadorDestino);
                resultadoDestino = resultadoDestino.comRegistros(resultadoPendencias);
            } else {
                log.info("⏭️ [DESTINO: {}] Reprocessamento de pendências desabilitado para {}.", destino, request.modo());
            }

            int pagina = 1;
            AssinaturaPagina assinaturaPaginaAnterior = null;
            String sinceParam = obterSinceParam(request);

            while (pagina <= request.maxPaginas()) {
                String invoiceKeyParam = request.retroativo() ? null : obterInvoiceKeyParam(destino);
                log.info(
                        "🔎 [DESTINO: {}] Página {}: buscando ocorrências a partir do cursor {}. invoice_key={} since={} occurrence_code={}",
                        destino,
                        pagina,
                        cursorAtual,
                        invoiceKeyParam,
                        sinceParam,
                        CODIGO_ENTREGA_REALIZADA
                );

                eslRequestPolicyService.aguardarProximaRequisicao();
                EslLoteResponseDTO lote = rodogarciaClient.buscarOcorrencias(
                        headerAuth,
                        cursorAtual,
                        invoiceKeyParam,
                        sinceParam,
                        CODIGO_ENTREGA_REALIZADA
                );
                if (loteVazio(lote)) {
                    log.info("📭 [DESTINO: {}] Nenhuma ocorrência encontrada a partir do cursor {}.", destino, cursorAtual);
                    resultadoDestino = resultadoDestino.encerrar("Nenhuma ocorrencia encontrada");
                    return resultadoDestino;
                }

                Long cursorRetornadoPelaEsl = obterCursorRetornadoPelaEsl(lote);
                Long cursorParaPersistir = obterCursorParaPersistir(lote);
                AssinaturaPagina assinaturaPaginaAtual = AssinaturaPagina.criar(lote);

                ResultadoPagina resultado = processarPagina(
                        destino,
                        headerAuth,
                        cursorParaPersistir,
                        lote,
                        request,
                        processadorDestino
                );
                resultadoDestino = resultadoDestino.comPagina(resultado);

                log.info(
                        "📄 [DESTINO: {}] Página {} finalizada: lidas={}, enviadas={}, ignoradas={}, já_processadas={}, falhas={}.",
                        destino,
                        pagina,
                        resultado.recebidos(),
                        resultado.enviados(),
                        resultado.ignorados(),
                        resultado.jaProcessados(),
                        resultado.erros()
                );

                if (resultado.interromperCiclo()) {
                    resultadoDestino = resultadoDestino.encerrar("Modo E2E processou a primeira nota forcada");
                    return resultadoDestino;
                }

                if (resultado.erros() > 0) {
                    log.warn(
                            "⚠️ [DESTINO: {}] Cursor não avançado: {} erro(s) na página. Próximo ciclo tentará novamente a partir de {}.",
                            destino,
                            resultado.erros(),
                            cursorAtual
                    );
                    resultadoDestino = resultadoDestino.encerrar("Cursor nao avancado por erro em registro");
                    return resultadoDestino;
                }

                if (resultado.fimJanelaRetroativa() || deveEncerrarPorDataFinal(request, lote)) {
                    log.info(
                            "🏁 [DESTINO: {}] Fim da janela retroativa: página {} ultrapassou a data final {}.",
                            destino,
                            pagina,
                            request.dataFinal()
                    );
                    resultadoDestino = resultadoDestino.encerrar("Fim da janela retroativa");
                    return resultadoDestino;
                }

                if (cursorParaPersistir == null) {
                    log.warn("⚠️ [DESTINO: {}] Página processada sem cursor de avanço retornado ou inferido.", destino);
                    resultadoDestino = resultadoDestino.encerrar("Pagina processada sem cursor de avanco");
                    return resultadoDestino;
                }

                ResultadoLoopPaginacao loopPaginacao = diagnosticarLoopPaginacao(
                        destino,
                        pagina,
                        cursorAtual,
                        cursorRetornadoPelaEsl,
                        cursorParaPersistir,
                        assinaturaPaginaAnterior,
                        assinaturaPaginaAtual
                );
                if (loopPaginacao.detectado()) {
                    if (paginaSemTrabalhoUtil(resultado)) {
                        log.info(
                                "🏁 [DESTINO: {}] Fim da fila: {} Página sem trabalho útil para o destino; encerrando sem erro crítico.",
                                destino,
                                loopPaginacao.mensagem()
                        );
                        resultadoDestino = resultadoDestino.encerrar(
                                "Fim da fila: cursor sem avanco apos pagina sem ocorrencias elegiveis"
                        );
                        return resultadoDestino;
                    }

                    log.error(
                            "💥 [DESTINO: {}] API da ESL travou: {} Encerrando ciclo para evitar repetição infinita.",
                            destino,
                            loopPaginacao.mensagem()
                    );
                    resultadoDestino = resultadoDestino.comErroCritico(loopPaginacao.mensagem());
                    return resultadoDestino;
                }

                if (request.persistirCursor()) {
                    salvarControleCursor(destino, cursorParaPersistir);
                } else {
                    log.info("📍 [DESTINO: {}] Cursor retroativo avançado em memória para {}.", destino, cursorParaPersistir);
                }

                assinaturaPaginaAnterior = assinaturaPaginaAtual;
                cursorAtual = cursorParaPersistir;
                pagina++;
            }

            log.warn(
                    "⏸️ [DESTINO: {}] Limite de {} página(s) por ciclo atingido.",
                    destino,
                    request.maxPaginas()
            );
            resultadoDestino = resultadoDestino.encerrar("Limite de paginas por ciclo atingido");
            return resultadoDestino;
        } catch (Exception e) {
            log.error("💥 [DESTINO: {}] Erro crítico no ciclo ETL - {}", destino, e.getMessage());
            resultadoDestino = resultadoDestino.comErroCritico(e);
            return resultadoDestino;
        } finally {
            log.info("""

                    {}
                    📊 RESUMO FINAL - DESTINO: {}
                    {}
                    📦 Total de Notas Lidas: {}
                    ⏭️  Ignoradas (Outro Status): {}
                    ⏳ Pendentes de Foto: {}
                    ♻️  Já Processadas: {}
                    ✅ Enviadas com Sucesso: {}
                    ❌ Falhas: {}
                    📄 Páginas Processadas: {}
                    💥 Erro Crítico: {}
                    🧭 Encerramento: {}
                    {}
                    """,
                    LINHA_BANNER,
                    resultadoDestino.destino(),
                    LINHA_BANNER,
                    resultadoDestino.recebidos(),
                    resultadoDestino.ignorados(),
                    resultadoDestino.pendentesFoto(),
                    resultadoDestino.jaProcessados(),
                    resultadoDestino.enviados(),
                    resultadoDestino.erros(),
                    resultadoDestino.paginasProcessadas(),
                    resultadoDestino.erroCritico(),
                    resultadoDestino.mensagemEncerramento(),
                    LINHA_BANNER
            );
        }
    }

    private ResultadoPagina processarPagina(
            String destino,
            String headerAuth,
            Long cursorNextId,
            EslLoteResponseDTO lote,
            ExecucaoEtlRequest request,
            ProcessadorDestino processadorDestino
    ) {
        ResultadoPagina resultado = ResultadoPagina.vazio();
        int indice = 0;

        for (EslOcorrenciaDTO ocorrencia : lote.data()) {
            if (ocorrenciaDepoisDataFinal(request, ocorrencia)) {
                log.info(
                        "⏭️ [DESTINO: {}] occurrence_id={} ignorada fora da janela retroativa. data_referencia={} data_final={}",
                        destino,
                        obterOccurrenceId(ocorrencia),
                        obterDataReferenciaPeriodo(ocorrencia),
                        request.dataFinal()
                );
                return resultado.comFimJanelaRetroativa();
            }

            ResultadoRegistro registro = processarOcorrencia(destino, headerAuth, cursorNextId, ocorrencia, processadorDestino);
            resultado = resultado.com(registro);

            if (modoTesteE2eImagem && indice == 0) {
                log.warn(
                        "🧪 [DESTINO: {}] Modo E2E de imagem ativo: primeira nota da página processada com status {}. Encerrando laço de teste.",
                        destino,
                        registro
                );
                return resultado.comInterrupcaoDeCiclo();
            }

            indice++;
        }

        return resultado;
    }

    private ResultadoPagina processarPendenciasDestino(
            String destino,
            String headerAuth,
            ProcessadorDestino processadorDestino
    ) {
        List<LogIntegracaoModel> pendencias = logIntegracaoRepository
                .findBySistemaDestinoAndStatusCanhotoOrderByDataProcessamentoAscIdAsc(destino, STATUS_PENDENTE_FOTO);
        if (pendencias == null || pendencias.isEmpty()) {
            return ResultadoPagina.vazio();
        }

        ResultadoPagina resultado = ResultadoPagina.vazio();
        log.info("🔁 [DESTINO: {}] Reprocessando {} canhoto(s) pendente(s).", destino, pendencias.size());

        for (LogIntegracaoModel pendencia : pendencias) {
            try {
                Optional<EslOcorrenciaDTO> ocorrencia = buscarOcorrenciaPendente(headerAuth, pendencia);
                if (ocorrencia.isEmpty()) {
                    manterPendenteSemOcorrencia(pendencia);
                    resultado = resultado.com(ResultadoRegistro.PENDENTE_FOTO);
                    continue;
                }

                ResultadoRegistro registro = processarOcorrenciaComLog(
                        destino,
                        headerAuth,
                        pendencia.getCursorNextId(),
                        ocorrencia.get(),
                        processadorDestino,
                        pendencia
                );
                resultado = resultado.com(registro);
            } catch (Exception e) {
                aplicarResultadoIntegracao(pendencia, ResultadoIntegracao.erroCanhoto(
                        statusDadosAtualOuSucesso(pendencia),
                        e.getMessage()
                ));
                logIntegracaoRepository.save(pendencia);
                log.error(
                        "❌ [DESTINO: {}] Erro ao reprocessar canhoto pendente da NF {} - {}",
                        destino,
                        pendencia.getChaveNfe(),
                        e.getMessage()
                );
                resultado = resultado.com(ResultadoRegistro.ERRO);
            }
        }

        return resultado;
    }

    private ResultadoRegistro processarOcorrencia(
            String destino,
            String headerAuth,
            Long cursorNextId,
            EslOcorrenciaDTO ocorrencia,
            ProcessadorDestino processadorDestino
    ) {
        Optional<LogIntegracaoModel> logExistente = buscarLogIntegracaoExistente(destino, ocorrencia);
        if (logExistente.isPresent() && finalizadoSemReenvio(logExistente.get())) {
            log.info(
                    "♻️ [{}] NF {}: Pulando (Já processada anteriormente). occurrence_id={}",
                    destino,
                    obterChaveNfe(ocorrencia),
                    obterOccurrenceId(ocorrencia)
            );
            return ResultadoRegistro.JA_PROCESSADO;
        }

        if (logExistente.isPresent() && limiteTentativasAtingido(logExistente.get())) {
            logarLimiteTentativasAtingido(destino, ocorrencia, logExistente.get());
            return ResultadoRegistro.JA_PROCESSADO;
        }

        LogIntegracaoModel logIntegracao = logExistente
                .orElseGet(() -> criarLogComStatus(destino, cursorNextId, ocorrencia, STATUS_RECEBIDO));
        return processarOcorrenciaComLog(destino, headerAuth, cursorNextId, ocorrencia, processadorDestino, logIntegracao);
    }

    private ResultadoRegistro processarOcorrenciaComLog(
            String destino,
            String headerAuth,
            Long cursorNextId,
            EslOcorrenciaDTO ocorrencia,
            ProcessadorDestino processadorDestino,
            LogIntegracaoModel logIntegracao
    ) {
        try {
            logIntegracao.setCursorNextId(cursorNextId);
            if (logIntegracao.getStatus() == null) {
                logIntegracao.setStatus(STATUS_RECEBIDO);
            }
            logIntegracao.setDataProcessamento(LocalDateTime.now());
            logIntegracao = logIntegracaoRepository.save(logIntegracao);

            if (!modoTesteE2eImagem && !ehEntregaRealizada(ocorrencia)) {
                aplicarResultadoIntegracao(logIntegracao, ResultadoIntegracao.ignorado());
                logIntegracaoRepository.save(logIntegracao);

                log.info("⏭️ [{}] NF {}: Ignorada (Código diferente de 1).", destino, obterChaveNfe(ocorrencia));
                return ResultadoRegistro.IGNORADO;
            }

            if (modoTesteE2eImagem && !ehEntregaRealizada(ocorrencia)) {
                log.warn(
                        "🧪 [{}] NF {}: Modo E2E ativo, filtro occurrence.code == 1 bypassado. codigo_origem={}",
                        destino,
                        obterChaveNfe(ocorrencia),
                        ocorrencia != null && ocorrencia.occurrence() != null ? ocorrencia.occurrence().code() : null
                );
            }

            if (!notaFiscalPermitidaPorDestino(destino, ocorrencia)) {
                aplicarResultadoIntegracao(logIntegracao, ResultadoIntegracao.ignorado());
                logIntegracaoRepository.save(logIntegracao);
                return ResultadoRegistro.IGNORADO;
            }

            log.info("📄 [{}] NF {}: Buscando comprovante de entrega na ESL...", destino, obterChaveNfe(ocorrencia));
            ComprovanteEslDTO comprovante = buscarComprovanteEntregaOpcional(headerAuth, ocorrencia);

            String chave = obterChaveNfe(ocorrencia);
            ComprovanteEslDTO comprovanteProcessamento = prepararComprovanteParaModoTeste(comprovante, chave, destino);
            if (!comprovanteTemUrlImagem(comprovanteProcessamento)) {
                comprovanteProcessamento = null;
            }

            if (DESTINO_PPG.equals(destino) && comprovanteProcessamento == null) {
                ResultadoIntegracao resultadoPendente = ResultadoIntegracao.pendenteFotoPpg("Canhoto ainda não disponível na ESL");
                aplicarResultadoIntegracao(logIntegracao, resultadoPendente);
                logIntegracaoRepository.save(logIntegracao);

                log.warn("⏳ [PPG] NF {}: Canhoto ainda não disponível na ESL. Payload não enviado.", chave);
                return ResultadoRegistro.PENDENTE_FOTO;
            }

            if (comprovanteProcessamento != null) {
                log.info("⬇️ [{}] NF {}: Baixando imagem do canhoto...", destino, chave);
            }

            ResultadoIntegracao resultadoProcessador;
            try {
                resultadoProcessador = processadorDestino.processar(ocorrencia, comprovanteProcessamento, logIntegracao);
            } catch (Exception e) {
                if (!modoTesteE2eImagem || comprovanteUsaImagemTeste(comprovanteProcessamento)) {
                    throw e;
                }

                log.warn(
                        "🧪 [{}] NF {}: Falha na imagem real, injetando imagem pública de teste para E2E. mensagem={}",
                        destino,
                        chave,
                        e.getMessage()
                );
                resultadoProcessador = processadorDestino.processar(ocorrencia, criarComprovanteComImagemTeste(), logIntegracao);
            }

            aplicarResultadoIntegracao(logIntegracao, resultadoProcessador);
            logIntegracaoRepository.save(logIntegracao);

            ResultadoRegistro resultadoRegistro = converterResultadoRegistro(resultadoProcessador);
            if (resultadoRegistro == ResultadoRegistro.IGNORADO) {
                log.info("⏭️ [{}] NF {}: Ignorada pelo destino.", destino, chave);
                return ResultadoRegistro.IGNORADO;
            }

            if (resultadoRegistro == ResultadoRegistro.PENDENTE_FOTO) {
                log.info("⏳ [{}] NF {}: Aguardando canhoto para concluir o destino.", destino, chave);
                return ResultadoRegistro.PENDENTE_FOTO;
            }

            if (resultadoRegistro == ResultadoRegistro.ERRO) {
                log.error("❌ [{}] NF {}: Destino retornou erro controlado.", destino, chave);
                return resultadoErroRespeitandoLimiteTentativas(destino, ocorrencia, logIntegracao);
            }

            log.info("✅ [{}] NF {}: Processamento do destino concluído com sucesso!", destino, chave);
            return ResultadoRegistro.ENVIADO;
        } catch (Exception e) {
            aplicarResultadoIntegracao(logIntegracao, criarResultadoErroGenerico(destino, e));
            logIntegracaoRepository.save(logIntegracao);

            log.error("❌ [{}] NF {}: Erro ao processar - {}", destino, obterChaveNfe(ocorrencia), e.getMessage());
            return resultadoErroRespeitandoLimiteTentativas(destino, ocorrencia, logIntegracao);
        }
    }

    private Optional<EslOcorrenciaDTO> buscarOcorrenciaPendente(String headerAuth, LogIntegracaoModel pendencia) {
        String chaveNfe = pendencia.getChaveNfe();
        if (chaveNfe == null || chaveNfe.isBlank()) {
            return Optional.empty();
        }

        eslRequestPolicyService.aguardarProximaRequisicao();
        EslLoteResponseDTO lote = rodogarciaClient.buscarOcorrencias(
                headerAuth,
                null,
                chaveNfe,
                null,
                CODIGO_ENTREGA_REALIZADA
        );
        if (loteVazio(lote)) {
            return Optional.empty();
        }

        return lote.data().stream()
                .filter(ocorrencia -> ocorrencia != null)
                .filter(ocorrencia -> pendencia.getOccurrenceId() == null
                        || pendencia.getOccurrenceId().equals(ocorrencia.id()))
                .findFirst();
    }

    private void manterPendenteSemOcorrencia(LogIntegracaoModel pendencia) {
        pendencia.setMensagemErroCanhoto("Ocorrência não encontrada na ESL para retry por invoice_key");
        pendencia.setStatusCanhoto(STATUS_PENDENTE_FOTO);
        if (pendencia.getStatus() == null || STATUS_RECEBIDO.equals(pendencia.getStatus())) {
            pendencia.setStatus(STATUS_PENDENTE_FOTO);
        }
        pendencia.setDataProcessamento(LocalDateTime.now());
        logIntegracaoRepository.save(pendencia);
    }

    private Optional<LogIntegracaoModel> buscarLogIntegracaoExistente(String destino, EslOcorrenciaDTO ocorrencia) {
        Long occurrenceId = obterOccurrenceId(ocorrencia);
        if (occurrenceId == null) {
            return Optional.empty();
        }

        return logIntegracaoRepository.findTopBySistemaDestinoAndOccurrenceIdOrderByDataProcessamentoDescIdDesc(
                destino,
                occurrenceId
        );
    }

    private boolean finalizadoSemReenvio(LogIntegracaoModel logIntegracao) {
        return logIntegracao != null && STATUS_FINALIZADOS_SEM_REENVIO.contains(logIntegracao.getStatus());
    }

    private ResultadoRegistro resultadoErroRespeitandoLimiteTentativas(
            String destino,
            EslOcorrenciaDTO ocorrencia,
            LogIntegracaoModel logIntegracao
    ) {
        if (limiteTentativasAtingido(logIntegracao)) {
            logarLimiteTentativasAtingido(destino, ocorrencia, logIntegracao);
            return ResultadoRegistro.JA_PROCESSADO;
        }

        return ResultadoRegistro.ERRO;
    }

    private boolean limiteTentativasAtingido(LogIntegracaoModel logIntegracao) {
        if (logIntegracao == null) {
            return false;
        }

        return limiteTentativasAtingido(logIntegracao.getStatusDados(), logIntegracao.getTentativasDados())
                || limiteTentativasAtingido(logIntegracao.getStatusCanhoto(), logIntegracao.getTentativasCanhoto());
    }

    private boolean limiteTentativasAtingido(String status, Integer tentativas) {
        return STATUS_ERRO_DESTINO.equals(status)
                && valorTentativas(tentativas) >= limiteMaximoTentativas();
    }

    private int valorTentativas(Integer tentativas) {
        return tentativas == null ? 0 : tentativas;
    }

    private int limiteMaximoTentativas() {
        return Math.max(1, maxTentativasReprocessamento);
    }

    private void logarLimiteTentativasAtingido(
            String destino,
            EslOcorrenciaDTO ocorrencia,
            LogIntegracaoModel logIntegracao
    ) {
        log.warn(
                "🛑 [{}] NF {}: Limite de {} tentativa(s) atingido. Mantendo ERRO_DESTINO para o Dashboard e bloqueando retry automático. tentativas_dados={} tentativas_canhoto={}",
                destino,
                obterChaveNfe(ocorrencia),
                limiteMaximoTentativas(),
                valorTentativas(logIntegracao.getTentativasDados()),
                valorTentativas(logIntegracao.getTentativasCanhoto())
        );
    }

    private void aplicarResultadoIntegracao(LogIntegracaoModel logIntegracao, ResultadoIntegracao resultado) {
        LocalDateTime agora = LocalDateTime.now();
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
    }

    private boolean deveAtualizarDataProcessamento(String statusAnterior, String statusNovo) {
        return statusNovo != null
                && !ResultadoIntegracao.STATUS_NAO_APLICAVEL.equals(statusNovo)
                && (STATUS_ERRO_DESTINO.equals(statusNovo) || !statusNovo.equals(statusAnterior));
    }

    private Integer incrementar(Integer valorAtual) {
        return valorAtual == null ? 1 : valorAtual + 1;
    }

    private String montarMensagemErroGeral(ResultadoIntegracao resultado) {
        if (resultado.mensagemErroDados() != null && resultado.mensagemErroCanhoto() != null) {
            return resultado.mensagemErroDados() + " | " + resultado.mensagemErroCanhoto();
        }

        if (resultado.mensagemErroDados() != null) {
            return resultado.mensagemErroDados();
        }

        return resultado.mensagemErroCanhoto();
    }

    private ResultadoRegistro converterResultadoRegistro(ResultadoIntegracao resultado) {
        if (resultado.erro()) {
            return ResultadoRegistro.ERRO;
        }

        if (resultado.foiIgnorado()) {
            return ResultadoRegistro.IGNORADO;
        }

        if (resultado.pendenteFoto()) {
            return ResultadoRegistro.PENDENTE_FOTO;
        }

        return ResultadoRegistro.ENVIADO;
    }

    private ResultadoIntegracao criarResultadoErroGenerico(String destino, Exception e) {
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

    private String statusDadosAtualOuSucesso(LogIntegracaoModel logIntegracao) {
        if (logIntegracao.getStatusDados() == null || logIntegracao.getStatusDados().isBlank()) {
            return STATUS_SUCESSO;
        }

        return logIntegracao.getStatusDados();
    }

    private boolean statusSucesso(String status) {
        return STATUS_SUCESSO.equals(status);
    }

    private String obterSinceParam(ExecucaoEtlRequest request) {
        if (!request.retroativo()) {
            return null;
        }

        return request.dataInicial()
                .atStartOfDay()
                .atOffset(OFFSET_SINCE_ESL)
                .format(ESL_SINCE_FORMATTER);
    }

    private boolean ocorrenciaDepoisDataFinal(ExecucaoEtlRequest request, EslOcorrenciaDTO ocorrencia) {
        if (!request.retroativo()) {
            return false;
        }

        OffsetDateTime dataReferencia = obterDataReferenciaPeriodo(ocorrencia);
        return dataReferencia != null && dataReferencia.toLocalDate().isAfter(request.dataFinal());
    }

    private boolean deveEncerrarPorDataFinal(ExecucaoEtlRequest request, EslLoteResponseDTO lote) {
        if (!request.retroativo() || loteVazio(lote)) {
            return false;
        }

        boolean algumaDataPresente = lote.data().stream()
                .map(this::obterDataReferenciaPeriodo)
                .anyMatch(data -> data != null);
        if (!algumaDataPresente) {
            return false;
        }

        boolean todosComDataUltrapassaram = lote.data().stream()
                .map(this::obterDataReferenciaPeriodo)
                .filter(data -> data != null)
                .allMatch(data -> data.toLocalDate().isAfter(request.dataFinal()));

        EslOcorrenciaDTO ultimaOcorrencia = lote.data().get(lote.data().size() - 1);
        OffsetDateTime dataUltimaOcorrencia = obterDataReferenciaPeriodo(ultimaOcorrencia);
        boolean ultimaUltrapassou = dataUltimaOcorrencia != null
                && dataUltimaOcorrencia.toLocalDate().isAfter(request.dataFinal());

        return todosComDataUltrapassaram || ultimaUltrapassou;
    }

    private OffsetDateTime obterDataReferenciaPeriodo(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia == null) {
            return null;
        }

        if (ocorrencia.createdAt() != null) {
            return ocorrencia.createdAt();
        }

        return ocorrencia.occurrenceAt();
    }

    private Long buscarUltimoCursor(String destino) {
        return controleCursorRepository.findBySistemaDestino(destino)
                .map(ControleCursor::getCursorNextId)
                .orElse(null);
    }

    private void salvarControleCursor(String destino, Long cursorNextId) {
        ControleCursor controleCursor = controleCursorRepository.findBySistemaDestino(destino)
                .orElseGet(() -> ControleCursor.builder()
                        .sistemaDestino(destino)
                        .build());

        controleCursor.setCursorNextId(cursorNextId);
        controleCursor.setDataAtualizacao(LocalDateTime.now());
        controleCursorRepository.save(controleCursor);
        log.info("📍 [DESTINO: {}] Cursor avançado para {}.", destino, cursorNextId);
    }

    private boolean loteVazio(EslLoteResponseDTO lote) {
        return lote == null || lote.data() == null || lote.data().isEmpty();
    }

    private String obterInvoiceKeyParam(String destino) {
        String whitelist = obterWhitelistNfePorDestino(destino);
        if (whitelist == null || whitelist.isBlank()) {
            return null;
        }

        return obterPrimeiraChaveWhitelist(whitelist);
    }

    private String obterWhitelistNfePorDestino(String destino) {
        if (DESTINO_PPG.equals(destino) && ppgNfeWhitelistEnabled) {
            return ppgNfeWhitelist;
        }

        if (DESTINO_VEDACIT.equals(destino) && vedacitNfeWhitelistEnabled) {
            return vedacitNfeWhitelist;
        }

        return null;
    }

    private String obterPrimeiraChaveWhitelist(String whitelist) {
        if (whitelist == null || whitelist.isBlank()) {
            return null;
        }

        return Arrays.stream(whitelist.split(","))
                .map(String::trim)
                .filter(chave -> !chave.isBlank())
                .findFirst()
                .orElse(null);
    }

    private boolean notaFiscalPermitidaPorDestino(String destino, EslOcorrenciaDTO ocorrencia) {
        if (DESTINO_PPG.equals(destino)) {
            return ppgIntegrationService.notaFiscalPermitida(ocorrencia);
        }

        if (DESTINO_VEDACIT.equals(destino)) {
            return vedacitIntegrationService.notaFiscalPermitida(ocorrencia);
        }

        return true;
    }

    private Long obterCursorParaPersistir(EslLoteResponseDTO lote) {
        Long nextId = lote.paging() != null ? lote.paging().nextId() : null;
        if (nextId != null) {
            return nextId;
        }

        return lote.data().stream()
                .filter(ocorrencia -> ocorrencia != null && ocorrencia.id() != null)
                .mapToLong(EslOcorrenciaDTO::id)
                .max()
                .stream()
                .boxed()
                .findFirst()
                .orElse(null);
    }

    private Long obterCursorRetornadoPelaEsl(EslLoteResponseDTO lote) {
        return lote.paging() != null ? lote.paging().nextId() : null;
    }

    private boolean paginaSemTrabalhoUtil(ResultadoPagina resultado) {
        return resultado.enviados() == 0
                && resultado.pendentesFoto() == 0
                && resultado.erros() == 0;
    }

    private ResultadoLoopPaginacao diagnosticarLoopPaginacao(
            String destino,
            int pagina,
            Long cursorRequisitado,
            Long cursorRetornadoPelaEsl,
            Long cursorParaPersistir,
            AssinaturaPagina assinaturaPaginaAnterior,
            AssinaturaPagina assinaturaPaginaAtual
    ) {
        if (!cursorNaoAvancou(cursorRequisitado, cursorRetornadoPelaEsl, cursorParaPersistir)) {
            return ResultadoLoopPaginacao.naoDetectado();
        }

        if (cursorRequisitado != null && cursorRequisitado.equals(cursorRetornadoPelaEsl)) {
            return ResultadoLoopPaginacao.detectado(
                    "Loop crítico de paginação ESL: next_id retornado pela API (" + cursorRetornadoPelaEsl
                            + ") é igual ao next_id requisitado na página " + pagina + "."
            );
        }

        if (cursorRequisitado != null && cursorRequisitado.equals(cursorParaPersistir)) {
            return ResultadoLoopPaginacao.detectado(
                    "Loop crítico de paginação ESL: cursor calculado para persistência (" + cursorParaPersistir
                            + ") não avançou na página " + pagina + "."
            );
        }

        if (assinaturaPaginaAnterior != null && assinaturaPaginaAtual.mesmoConteudo(assinaturaPaginaAnterior)) {
            return ResultadoLoopPaginacao.detectado(
                    "Loop crítico de paginação ESL: página " + pagina + " repetiu os mesmos dados da página anterior"
                            + " em " + destino + " (total=" + assinaturaPaginaAtual.totalItens()
                            + ", primeiro_id=" + assinaturaPaginaAtual.primeiroOccurrenceId()
                            + ", ultimo_id=" + assinaturaPaginaAtual.ultimoOccurrenceId() + ")."
            );
        }

        return ResultadoLoopPaginacao.naoDetectado();
    }

    private boolean cursorNaoAvancou(
            Long cursorRequisitado,
            Long cursorRetornadoPelaEsl,
            Long cursorParaPersistir
    ) {
        return cursorRequisitado != null
                && (cursorRequisitado.equals(cursorRetornadoPelaEsl)
                || cursorRequisitado.equals(cursorParaPersistir));
    }

    private LogIntegracaoModel criarLogComStatus(
            String destino,
            Long cursorNextId,
            EslOcorrenciaDTO ocorrencia,
            String status
    ) {
        return LogIntegracaoModel.builder()
                .occurrenceId(obterOccurrenceId(ocorrencia))
                .chaveNfe(obterChaveNfe(ocorrencia))
                .freightId(ocorrencia != null && ocorrencia.freight() != null ? ocorrencia.freight().id() : null)
                .cursorNextId(cursorNextId)
                .status(status)
                .statusDados(status)
                .statusCanhoto(status)
                .tentativasDados(0)
                .tentativasCanhoto(0)
                .sistemaDestino(destino)
                .dataProcessamento(LocalDateTime.now())
                .build();
    }

    private ComprovanteEslDTO buscarComprovanteEntregaOpcional(String headerAuth, EslOcorrenciaDTO ocorrencia) {
        String cteKey = obterChaveCte(ocorrencia);

        if (cteKey == null || cteKey.isBlank()) {
            throw new IllegalStateException("Chave CTe ausente para consulta do comprovante");
        }

        eslRequestPolicyService.aguardarProximaRequisicao();
        ComprovanteEslDTO comprovante = rodogarciaClient.buscarComprovante(headerAuth, cteKey);

        if (comprovante == null || comprovante.data() == null || comprovante.data().isEmpty()) {
            return null;
        }

        return comprovante;
    }

    private ComprovanteEslDTO prepararComprovanteParaModoTeste(
            ComprovanteEslDTO comprovante,
            String chaveNfe,
            String destino
    ) {
        if (!modoTesteE2eImagem || comprovanteTemUrlImagem(comprovante)) {
            return comprovante;
        }

        log.warn(
                "🧪 [{}] NF {}: Comprovante veio sem image_url. Injetando URL pública de teste.",
                destino,
                chaveNfe
        );
        return criarComprovanteComImagemTeste();
    }

    private boolean comprovanteTemUrlImagem(ComprovanteEslDTO comprovante) {
        String urlImagem = obterPrimeiraUrlImagem(comprovante);
        return urlImagem != null && !urlImagem.isBlank();
    }

    private boolean comprovanteUsaImagemTeste(ComprovanteEslDTO comprovante) {
        return obterUrlImagemTeste().equals(obterPrimeiraUrlImagem(comprovante));
    }

    private String obterPrimeiraUrlImagem(ComprovanteEslDTO comprovante) {
        if (comprovante == null || comprovante.data() == null || comprovante.data().isEmpty()) {
            return null;
        }

        ComprovanteEslItemDTO primeiroComprovante = comprovante.data().get(0);
        return primeiroComprovante != null ? primeiroComprovante.imageUrl() : null;
    }

    private ComprovanteEslDTO criarComprovanteComImagemTeste() {
        ComprovanteEslItemDTO comprovanteTeste = new ComprovanteEslItemDTO(
                null,
                obterUrlImagemTeste(),
                null,
                null,
                null
        );

        return new ComprovanteEslDTO(List.of(comprovanteTeste), null);
    }

    private String obterUrlImagemTeste() {
        if (urlImagemTesteE2e == null || urlImagemTesteE2e.isBlank()) {
            return URL_IMAGEM_TESTE_PADRAO;
        }

        return urlImagemTesteE2e;
    }

    private boolean ehEntregaRealizada(EslOcorrenciaDTO ocorrencia) {
        return ocorrencia != null
                && ocorrencia.occurrence() != null
                && ocorrencia.occurrence().code() != null
                && ocorrencia.occurrence().code() == CODIGO_ENTREGA_REALIZADA;
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
        if (ocorrencia == null || ocorrencia.freight() == null) {
            return null;
        }

        return ocorrencia.freight().cteKey();
    }

    private enum ResultadoRegistro {
        ENVIADO,
        IGNORADO,
        JA_PROCESSADO,
        PENDENTE_FOTO,
        ERRO
    }

    @FunctionalInterface
    private interface ProcessadorDestino {
        ResultadoIntegracao processar(
                EslOcorrenciaDTO ocorrencia,
                ComprovanteEslDTO comprovante,
                LogIntegracaoModel logIntegracao
        );
    }

    private record ResultadoDestino(
            String destino,
            int paginasProcessadas,
            int recebidos,
            int enviados,
            int ignorados,
            int pendentesFoto,
            int jaProcessados,
            int erros,
            boolean erroCritico,
            String mensagemEncerramento
    ) {
        private static ResultadoDestino vazio(String destino) {
            return new ResultadoDestino(destino, 0, 0, 0, 0, 0, 0, 0, false, "Sem paginas processadas");
        }

        private static ResultadoDestino desabilitado(String destino) {
            return new ResultadoDestino(destino, 0, 0, 0, 0, 0, 0, 0, false, "Destino desabilitado por feature toggle");
        }

        private static ResultadoDestino naoSelecionado(String destino) {
            return new ResultadoDestino(destino, 0, 0, 0, 0, 0, 0, 0, false, "Destino nao selecionado");
        }

        private ResultadoDestino comPagina(ResultadoPagina pagina) {
            return new ResultadoDestino(
                    destino,
                    paginasProcessadas + 1,
                    recebidos + pagina.recebidos(),
                    enviados + pagina.enviados(),
                    ignorados + pagina.ignorados(),
                    pendentesFoto + pagina.pendentesFoto(),
                    jaProcessados + pagina.jaProcessados(),
                    erros + pagina.erros(),
                    erroCritico,
                    mensagemEncerramento
            );
        }

        private ResultadoDestino comRegistros(ResultadoPagina pagina) {
            return new ResultadoDestino(
                    destino,
                    paginasProcessadas,
                    recebidos + pagina.recebidos(),
                    enviados + pagina.enviados(),
                    ignorados + pagina.ignorados(),
                    pendentesFoto + pagina.pendentesFoto(),
                    jaProcessados + pagina.jaProcessados(),
                    erros + pagina.erros(),
                    erroCritico,
                    mensagemEncerramento
            );
        }

        private ResultadoDestino encerrar(String mensagemEncerramento) {
            return new ResultadoDestino(
                    destino,
                    paginasProcessadas,
                    recebidos,
                    enviados,
                    ignorados,
                    pendentesFoto,
                    jaProcessados,
                    erros,
                    erroCritico,
                    mensagemEncerramento
            );
        }

        private ResultadoDestino comErroCritico(Exception e) {
            String mensagemErro = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new ResultadoDestino(
                    destino,
                    paginasProcessadas,
                    recebidos,
                    enviados,
                    ignorados,
                    pendentesFoto,
                    jaProcessados,
                    erros + 1,
                    true,
                    "Erro critico: " + mensagemErro
            );
        }

        private ResultadoDestino comErroCritico(String mensagemErro) {
            return new ResultadoDestino(
                    destino,
                    paginasProcessadas,
                    recebidos,
                    enviados,
                    ignorados,
                    pendentesFoto,
                    jaProcessados,
                    erros + 1,
                    true,
                    mensagemErro
            );
        }
    }

    private record ResultadoPagina(
            int recebidos,
            int enviados,
            int ignorados,
            int pendentesFoto,
            int jaProcessados,
            int erros,
            boolean interromperCiclo,
            boolean fimJanelaRetroativa
    ) {
        private static ResultadoPagina vazio() {
            return new ResultadoPagina(0, 0, 0, 0, 0, 0, false, false);
        }

        private ResultadoPagina com(ResultadoRegistro registro) {
            return new ResultadoPagina(
                    recebidos + 1,
                    enviados + (registro == ResultadoRegistro.ENVIADO ? 1 : 0),
                    ignorados + (registro == ResultadoRegistro.IGNORADO ? 1 : 0),
                    pendentesFoto + (registro == ResultadoRegistro.PENDENTE_FOTO ? 1 : 0),
                    jaProcessados + (registro == ResultadoRegistro.JA_PROCESSADO ? 1 : 0),
                    erros + (registro == ResultadoRegistro.ERRO ? 1 : 0),
                    interromperCiclo,
                    fimJanelaRetroativa
            );
        }

        private ResultadoPagina comInterrupcaoDeCiclo() {
            return new ResultadoPagina(
                    recebidos,
                    enviados,
                    ignorados,
                    pendentesFoto,
                    jaProcessados,
                    erros,
                    true,
                    fimJanelaRetroativa
            );
        }

        private ResultadoPagina comFimJanelaRetroativa() {
            return new ResultadoPagina(
                    recebidos,
                    enviados,
                    ignorados,
                    pendentesFoto,
                    jaProcessados,
                    erros,
                    interromperCiclo,
                    true
            );
        }
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

    private record ResultadoLoopPaginacao(boolean detectado, String mensagem) {
        private static ResultadoLoopPaginacao detectado(String mensagem) {
            return new ResultadoLoopPaginacao(true, mensagem);
        }

        private static ResultadoLoopPaginacao naoDetectado() {
            return new ResultadoLoopPaginacao(false, null);
        }
    }

    private record AssinaturaPagina(
            List<Long> occurrenceIds,
            int totalItens,
            Long primeiroOccurrenceId,
            Long ultimoOccurrenceId
    ) {
        private static AssinaturaPagina criar(EslLoteResponseDTO lote) {
            List<Long> occurrenceIds = lote.data().stream()
                    .map(ocorrencia -> ocorrencia != null ? ocorrencia.id() : null)
                    .toList();
            Long primeiroId = occurrenceIds.isEmpty() ? null : occurrenceIds.get(0);
            Long ultimoId = occurrenceIds.isEmpty() ? null : occurrenceIds.get(occurrenceIds.size() - 1);

            return new AssinaturaPagina(occurrenceIds, occurrenceIds.size(), primeiroId, ultimoId);
        }

        private boolean mesmoConteudo(AssinaturaPagina anterior) {
            return totalItens == anterior.totalItens()
                    && occurrenceIds.equals(anterior.occurrenceIds());
        }
    }
}
