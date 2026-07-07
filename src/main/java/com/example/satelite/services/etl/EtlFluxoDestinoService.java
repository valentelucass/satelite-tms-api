package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.EslLoteResponseDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.models.ControleCursor;
import com.example.satelite.repositories.ControleCursorRepository;
import com.example.satelite.services.etl.EslRequestPolicyService.EslRequestTransientException;

@Service
public class EtlFluxoDestinoService {

    private static final Logger log = LoggerFactory.getLogger(EtlFluxoDestinoService.class);

    private static final int CODIGO_ENTREGA_REALIZADA = 1;
    private static final String LINHA_BANNER = "==================================================";
    private static final ZoneOffset OFFSET_SINCE_ESL = ZoneOffset.of("-03:00");
    private static final DateTimeFormatter ESL_SINCE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final String DESTINO_PPG = "PPG";
    private static final String DESTINO_VEDACIT = "VEDACIT";
    private static final int LOOKBACK_INCREMENTAL_HORAS_PADRAO = 24;
    private static final int LIMITE_PADRAO_FALHAS_INFRAESTRUTURA_CONSECUTIVAS = 10;
    private static final long PAUSA_PADRAO_PACING_PAGINACAO_MS = 30000L;

    private final RodogarciaClient rodogarciaClient;
    private final ControleCursorRepository controleCursorRepository;
    private final EslRequestPolicyService eslRequestPolicyService;
    private final EtlRegistroService etlRegistroService;

    @Value("${VEDACIT_NFE_WHITELIST_ENABLED:false}")
    private boolean vedacitNfeWhitelistEnabled;

    @Value("${VEDACIT_NFE_WHITELIST:}")
    private String vedacitNfeWhitelist;

    @Value("${app.ppg.nfe-whitelist-enabled:false}")
    private boolean ppgNfeWhitelistEnabled;

    @Value("${app.ppg.nfe-whitelist:}")
    private String ppgNfeWhitelist;

    @Value("${ETL_CIRCUIT_BREAKER_TRANSIENT_FAILURE_THRESHOLD:10}")
    private int limiteFalhasInfraestruturaConsecutivasCircuitBreaker;

    @Value("${ETL_INCREMENTAL_LOOKBACK_HOURS:24}")
    private int lookbackIncrementalHoras = LOOKBACK_INCREMENTAL_HORAS_PADRAO;

    @Value("${ETL_PAGINATION_PACING_PAUSE_MS:30000}")
    private long pausaPacingPaginacaoMs = PAUSA_PADRAO_PACING_PAGINACAO_MS;

    public EtlFluxoDestinoService(
            RodogarciaClient rodogarciaClient,
            ControleCursorRepository controleCursorRepository,
            EslRequestPolicyService eslRequestPolicyService,
            EtlRegistroService etlRegistroService
    ) {
        this.rodogarciaClient = rodogarciaClient;
        this.controleCursorRepository = controleCursorRepository;
        this.eslRequestPolicyService = eslRequestPolicyService;
        this.etlRegistroService = etlRegistroService;
    }

    public ResultadoDestino executarFluxoDestino(
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
                ResultadoPagina resultadoPendencias = etlRegistroService.processarPendenciasDestino(
                        destino,
                        headerAuth,
                        processadorDestino
                );
                resultadoDestino = resultadoDestino.comRegistros(resultadoPendencias);
            } else {
                log.info("⏭️ [DESTINO: {}] Reprocessamento de pendências desabilitado para {}.", destino, request.modo());
            }

            int pagina = 1;
            int paginasDesdeUltimaPausa = 0;
            int falhasInfraestruturaConsecutivas = 0;
            AssinaturaPagina assinaturaPaginaAnterior = null;
            String sinceParam = obterSinceParam(request);

            while (true) {
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

                EslLoteResponseDTO lote;
                try {
                    lote = buscarOcorrenciasEsl(
                            destino,
                            pagina,
                            headerAuth,
                            cursorAtual,
                            invoiceKeyParam,
                            sinceParam
                    );
                } catch (EslRequestTransientException e) {
                    if (!falhaPaginaNaoCritica(e)) {
                        throw e;
                    }

                    ResultadoPagina resultadoFalhaPagina =
                            ResultadoPagina.falhaInfraestruturaPagina(falhasInfraestruturaConsecutivas);
                    resultadoDestino = resultadoDestino.comPagina(resultadoFalhaPagina).encerrar(
                            "Falha transitoria da ESL na pagina " + pagina + "; cursor nao avancado"
                    );
                    log.warn(
                            "⏸️ [DESTINO: {}] Página {} marcada como falha transitória da ESL. cursor={} operacao={} status={}. Cursor não avançado; próximo ciclo retomará do mesmo ponto.",
                            destino,
                            pagina,
                            cursorAtual,
                            e.operacao(),
                            e.status()
                    );
                    return resultadoDestino;
                }

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
                        falhasInfraestruturaConsecutivas,
                        processadorDestino
                );
                resultadoDestino = resultadoDestino.comPagina(resultado);
                falhasInfraestruturaConsecutivas = resultado.falhasInfraestruturaConsecutivas();

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

                if (resultado.circuitoAberto()) {
                    String mensagem = "Circuit Breaker Aberto: "
                            + resultado.falhasInfraestruturaConsecutivas()
                            + " falhas de infraestrutura consecutivas em " + destino;
                    log.error(
                            "🛑 [DESTINO: {}] {}. Cursor não avançado; próximo ciclo retomará de {}.",
                            destino,
                            mensagem,
                            cursorAtual
                    );
                    resultadoDestino = resultadoDestino.comErroCritico(mensagem);
                    return resultadoDestino;
                }

                if (resultado.interromperCiclo()) {
                    resultadoDestino = resultadoDestino.encerrar("Modo E2E processou a primeira nota forcada");
                    return resultadoDestino;
                }

                if (resultado.erros() > 0 && !request.retroativo()) {
                    log.warn(
                            "⚠️ [DESTINO: {}] Cursor não avançado: {} erro(s) na página. Próximo ciclo tentará novamente a partir de {}.",
                            destino,
                            resultado.erros(),
                            cursorAtual
                    );
                    resultadoDestino = resultadoDestino.encerrar("Cursor nao avancado por erro em registro");
                    return resultadoDestino;
                }

                if (resultado.erros() > 0) {
                    log.warn(
                            "⚠️ [DESTINO: {}] Página retroativa teve {} erro(s), já registrados em ERRO_DESTINO. A carga retroativa continuará avançando em memória para não interromper o histórico.",
                            destino,
                            resultado.erros()
                    );
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

                if (cursorRepetido(cursorAtual, cursorParaPersistir)) {
                    log.info(
                            "🏁 [DESTINO: {}] Fim de fila detectado: a origem não retornou um novo cursor (cursor repetido {}). Encerrando a paginação com sucesso.",
                            destino,
                            cursorParaPersistir
                    );
                    resultadoDestino = resultadoDestino.encerrar("Fim de fila: cursor repetido pela origem");
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
                paginasDesdeUltimaPausa++;

                if (devePausarPorPacing(request, paginasDesdeUltimaPausa)) {
                    pausarPorPacing(destino, request, paginasDesdeUltimaPausa, cursorAtual);
                    paginasDesdeUltimaPausa = 0;
                }
            }
        } catch (EslRequestTransientException e) {
            if (falhaPaginaNaoCritica(e)) {
                log.warn(
                        "⏸️ [DESTINO: {}] Ciclo encerrado sem erro crítico por falha transitória da ESL antes da paginação. operacao={} status={} mensagem={}",
                        destino,
                        e.operacao(),
                        e.status(),
                        e.getMessage()
                );
                resultadoDestino = resultadoDestino.comRegistros(
                        ResultadoPagina.falhaInfraestruturaPagina(0)
                ).encerrar("Falha transitoria da ESL antes da paginacao; cursor nao avancado");
                return resultadoDestino;
            }

            log.warn(
                    "⏸️ [DESTINO: {}] Ciclo suspenso por falha transitória da ESL. operacao={} status={} mensagem={}",
                    destino,
                    e.operacao(),
                    e.status(),
                    e.getMessage()
            );
            resultadoDestino = resultadoDestino.comErroCritico(e);
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

    private EslLoteResponseDTO buscarOcorrenciasEsl(
            String destino,
            int pagina,
            String headerAuth,
            Long cursorAtual,
            String invoiceKeyParam,
            String sinceParam
    ) {
        String operacao = "buscarOcorrencias destino=" + destino
                + " pagina=" + pagina
                + " cursor=" + cursorAtual
                + " invoice_key=" + invoiceKeyParam
                + " since=" + sinceParam;

        return eslRequestPolicyService.executar(
                operacao,
                () -> rodogarciaClient.buscarOcorrencias(
                        headerAuth,
                        cursorAtual,
                        invoiceKeyParam,
                        sinceParam,
                        CODIGO_ENTREGA_REALIZADA
                )
        );
    }

    ResultadoPagina processarPagina(
            String destino,
            String headerAuth,
            Long cursorNextId,
            EslLoteResponseDTO lote,
            ExecucaoEtlRequest request,
            int falhasInfraestruturaConsecutivasInicial,
            ProcessadorDestino processadorDestino
    ) {
        ResultadoPagina resultado = ResultadoPagina.vazio(falhasInfraestruturaConsecutivasInicial);
        int indice = 0;

        for (EslOcorrenciaDTO ocorrencia : lote.data()) {
            if (ocorrenciaDepoisDataFinal(request, ocorrencia)) {
                log.info(
                        "⏭️ [DESTINO: {}] occurrence_id={} ignorada fora da janela retroativa. data_referencia={} data_final={}",
                        destino,
                        etlRegistroService.obterOccurrenceId(ocorrencia),
                        obterDataReferenciaPeriodo(ocorrencia),
                        request.dataFinal()
                );
                return resultado.comFimJanelaRetroativa();
            }

            ResultadoRegistro registro = etlRegistroService.processarOcorrencia(
                    destino,
                    headerAuth,
                    cursorNextId,
                    ocorrencia,
                    processadorDestino
            );
            resultado = resultado.com(registro);

            if (resultado.falhasInfraestruturaConsecutivas() >= limiteCircuitBreaker()) {
                log.error(
                        "🛑 [DESTINO: {}] Circuit Breaker aberto após {} falha(s) de infraestrutura consecutiva(s). occurrence_id={} NF={}",
                        destino,
                        resultado.falhasInfraestruturaConsecutivas(),
                        etlRegistroService.obterOccurrenceId(ocorrencia),
                        etlRegistroService.obterChaveNfe(ocorrencia)
                );
                return resultado.comCircuitoAberto();
            }

            if (etlRegistroService.modoTesteE2eImagemAtivo() && indice == 0) {
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

    private int limiteCircuitBreaker() {
        return Math.max(
                1,
                limiteFalhasInfraestruturaConsecutivasCircuitBreaker > 0
                        ? limiteFalhasInfraestruturaConsecutivasCircuitBreaker
                        : LIMITE_PADRAO_FALHAS_INFRAESTRUTURA_CONSECUTIVAS
        );
    }

    private boolean falhaPaginaNaoCritica(EslRequestTransientException e) {
        return e.status() == EslRequestPolicyService.STATUS_SEM_RESPOSTA_HTTP
                || (e.status() >= 500 && e.status() <= 599);
    }

    private boolean devePausarPorPacing(ExecucaoEtlRequest request, int paginasDesdeUltimaPausa) {
        return paginasDesdeUltimaPausa >= request.maxPaginas();
    }

    private void pausarPorPacing(
            String destino,
            ExecucaoEtlRequest request,
            int paginasDesdeUltimaPausa,
            Long proximoCursor
    ) {
        long pausaMs = pausaPacingPaginacaoMs();
        log.info(
                "⏸️ [DESTINO: {}] Lote de {} página(s) processado no modo {}. Pausando {} ms antes de retomar do cursor {}.",
                destino,
                paginasDesdeUltimaPausa,
                request.modo(),
                pausaMs,
                proximoCursor
        );

        if (pausaMs <= 0) {
            return;
        }

        try {
            Thread.sleep(pausaMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Pausa de pacing da paginacao ESL interrompida", e);
        }
    }

    private long pausaPacingPaginacaoMs() {
        return Math.max(0, pausaPacingPaginacaoMs);
    }

    String obterSinceParam(ExecucaoEtlRequest request) {
        if (request.retroativo()) {
            return request.dataInicial()
                    .atStartOfDay()
                    .atOffset(OFFSET_SINCE_ESL)
                    .format(ESL_SINCE_FORMATTER);
        }

        if (lookbackIncrementalHoras <= 0) {
            return null;
        }

        return OffsetDateTime.now(OFFSET_SINCE_ESL)
                .minusHours(lookbackIncrementalHoras)
                .format(ESL_SINCE_FORMATTER);
    }

    boolean ocorrenciaDepoisDataFinal(ExecucaoEtlRequest request, EslOcorrenciaDTO ocorrencia) {
        if (!request.retroativo()) {
            return false;
        }

        OffsetDateTime dataReferencia = obterDataReferenciaPeriodo(ocorrencia);
        return dataReferencia != null && dataReferencia.toLocalDate().isAfter(request.dataFinal());
    }

    boolean deveEncerrarPorDataFinal(ExecucaoEtlRequest request, EslLoteResponseDTO lote) {
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

    OffsetDateTime obterDataReferenciaPeriodo(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia == null) {
            return null;
        }

        if (ocorrencia.createdAt() != null) {
            return ocorrencia.createdAt();
        }

        return ocorrencia.occurrenceAt();
    }

    Long buscarUltimoCursor(String destino) {
        return controleCursorRepository.findBySistemaDestino(destino)
                .map(ControleCursor::getCursorNextId)
                .orElse(null);
    }

    void salvarControleCursor(String destino, Long cursorNextId) {
        ControleCursor controleCursor = controleCursorRepository.findBySistemaDestino(destino)
                .orElseGet(() -> ControleCursor.builder()
                        .sistemaDestino(destino)
                        .build());

        controleCursor.setCursorNextId(cursorNextId);
        controleCursor.setDataAtualizacao(LocalDateTime.now());
        controleCursorRepository.save(controleCursor);
        log.info("📍 [DESTINO: {}] Cursor avançado para {}.", destino, cursorNextId);
    }

    boolean loteVazio(EslLoteResponseDTO lote) {
        return lote == null || lote.data() == null || lote.data().isEmpty();
    }

    String obterInvoiceKeyParam(String destino) {
        String whitelist = obterWhitelistNfePorDestino(destino);
        if (whitelist == null || whitelist.isBlank()) {
            return null;
        }

        return obterPrimeiraChaveWhitelist(whitelist);
    }

    String obterWhitelistNfePorDestino(String destino) {
        if (DESTINO_PPG.equals(destino) && ppgNfeWhitelistEnabled) {
            return ppgNfeWhitelist;
        }

        if (DESTINO_VEDACIT.equals(destino) && vedacitNfeWhitelistEnabled) {
            return vedacitNfeWhitelist;
        }

        return null;
    }

    String obterPrimeiraChaveWhitelist(String whitelist) {
        if (whitelist == null || whitelist.isBlank()) {
            return null;
        }

        return Arrays.stream(whitelist.split(","))
                .map(String::trim)
                .filter(chave -> !chave.isBlank())
                .findFirst()
                .orElse(null);
    }

    Long obterCursorParaPersistir(EslLoteResponseDTO lote) {
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

    Long obterCursorRetornadoPelaEsl(EslLoteResponseDTO lote) {
        return lote.paging() != null ? lote.paging().nextId() : null;
    }

    boolean cursorRepetido(Long cursorAtual, Long cursorParaPersistir) {
        return cursorAtual != null && cursorAtual.equals(cursorParaPersistir);
    }

    boolean paginaSemTrabalhoUtil(ResultadoPagina resultado) {
        return resultado.enviados() == 0
                && resultado.pendentesFoto() == 0
                && resultado.erros() == 0;
    }

    ResultadoLoopPaginacao diagnosticarLoopPaginacao(
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

    boolean cursorNaoAvancou(
            Long cursorRequisitado,
            Long cursorRetornadoPelaEsl,
            Long cursorParaPersistir
    ) {
        return cursorRequisitado != null
                && (cursorRequisitado.equals(cursorRetornadoPelaEsl)
                || cursorRequisitado.equals(cursorParaPersistir));
    }

    record ResultadoLoopPaginacao(boolean detectado, String mensagem) {
        static ResultadoLoopPaginacao detectado(String mensagem) {
            return new ResultadoLoopPaginacao(true, mensagem);
        }

        static ResultadoLoopPaginacao naoDetectado() {
            return new ResultadoLoopPaginacao(false, null);
        }
    }

    record AssinaturaPagina(
            List<Long> occurrenceIds,
            int totalItens,
            Long primeiroOccurrenceId,
            Long ultimoOccurrenceId
    ) {
        static AssinaturaPagina criar(EslLoteResponseDTO lote) {
            List<Long> occurrenceIds = lote.data().stream()
                    .map(ocorrencia -> ocorrencia != null ? ocorrencia.id() : null)
                    .toList();
            Long primeiroId = occurrenceIds.isEmpty() ? null : occurrenceIds.get(0);
            Long ultimoId = occurrenceIds.isEmpty() ? null : occurrenceIds.get(occurrenceIds.size() - 1);

            return new AssinaturaPagina(occurrenceIds, occurrenceIds.size(), primeiroId, ultimoId);
        }

        boolean mesmoConteudo(AssinaturaPagina anterior) {
            return totalItens == anterior.totalItens()
                    && occurrenceIds.equals(anterior.occurrenceIds());
        }
    }
}
