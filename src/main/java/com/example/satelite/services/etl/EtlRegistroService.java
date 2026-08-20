package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslItemDTO;
import com.example.satelite.dto.rodogarcia.EslFreightDTO;
import com.example.satelite.dto.rodogarcia.EslInvoiceDTO;
import com.example.satelite.dto.rodogarcia.EslLoteResponseDTO;
import com.example.satelite.dto.rodogarcia.EslOccurrenceDefDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.services.etl.EslRequestPolicyService.EslRequestTransientException;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.selia.SeliaIntegrationService;
import com.example.satelite.services.supporte.SupporteIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;
import com.example.satelite.services.vedacit.VedacitCteCanhotoReconciliationService;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocumentSource;

@Service
public class EtlRegistroService {

    private static final Logger log = LoggerFactory.getLogger(EtlRegistroService.class);
    private static final Logger logDetalheSftpVedacit = LoggerFactory.getLogger("satelite.vedacit.sftp.detail");

    private static final int CODIGO_ENTREGA_REALIZADA = 1;
    private static final String DESTINO_PPG = "PPG";
    private static final String DESTINO_SELIA = "SELIA";
    private static final String DESTINO_SUPPORTE = "SUPPORTE";
    private static final String DESTINO_VEDACIT = "VEDACIT";
    private static final String STATUS_RECEBIDO = ResultadoIntegracao.STATUS_RECEBIDO;
    private static final String STATUS_PENDENTE_FOTO = ResultadoIntegracao.STATUS_PENDENTE_FOTO;
    private static final String STATUS_ERRO_DESTINO = ResultadoIntegracao.STATUS_ERRO_DESTINO;
    private static final String STATUS_SUCESSO = ResultadoIntegracao.STATUS_SUCESSO;
    private static final String URL_IMAGEM_TESTE_PADRAO = "https://www.w3.org/People/mimasa/test/imgformat/img/w3c_home.jpg";
    private static final String MOTIVO_CANHOTO_INDISPONIVEL = "Canhoto ainda não disponível na ESL";
    private static final String MOTIVO_CTE_AUSENTE =
            "Chave CTe ausente na ocorrência ESL; busca do comprovante pulada";

    private final RodogarciaClient rodogarciaClient;
    private final EslRequestPolicyService eslRequestPolicyService;
    private final EtlResilienciaService etlResilienciaService;
    private final EtlEstadoIntegracaoService etlEstadoIntegracaoService;
    private final PpgIntegrationService ppgIntegrationService;
    private final VedacitIntegrationService vedacitIntegrationService;
    private final SeliaIntegrationService seliaIntegrationService;
    private final SupporteIntegrationService supporteIntegrationService;

    @Value("${APP_E2E_IMAGE_TEST_MODE:false}")
    private boolean modoTesteE2eImagem;

    @Value("${APP_E2E_TEST_IMAGE_URL:" + URL_IMAGEM_TESTE_PADRAO + "}")
    private String urlImagemTesteE2e;

    @Value("${RODOGARCIA_TOKEN_SELIA_COMPROVANTE:}")
    private String tokenSeliaComprovanteEsl;

    @Value("${RODOGARCIA_TOKEN_SUPPORTE_COMPROVANTE:}")
    private String tokenSupporteComprovanteEsl;

    @Value("${RODOGARCIA_TOKEN_VEDACIT_COMPROVANTE:}")
    private String tokenVedacitComprovanteEsl;

    @Value("${RODOGARCIA_MASTER_API_REST:}")
    private String tokenMasterEsl;

    @Value("${SFTP_RODOGARCIA_ENABLED:false}")
    private boolean sftpRodogarciaHabilitado;

    @Value("${VEDACIT_SFTP_RECONCILIATION_ENABLED:false}")
    private boolean reconciliacaoSftpVedacitHabilitada;

    private VedacitCteCanhotoReconciliationService vedacitCteCanhotoReconciliationService;

    @Autowired
    void configurarReconciliacaoVedacit(VedacitCteCanhotoReconciliationService service) {
        this.vedacitCteCanhotoReconciliationService = service;
    }

    @Autowired
    public EtlRegistroService(
            RodogarciaClient rodogarciaClient,
            EslRequestPolicyService eslRequestPolicyService,
            EtlResilienciaService etlResilienciaService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            PpgIntegrationService ppgIntegrationService,
            VedacitIntegrationService vedacitIntegrationService,
            SeliaIntegrationService seliaIntegrationService,
            SupporteIntegrationService supporteIntegrationService
    ) {
        this.rodogarciaClient = rodogarciaClient;
        this.eslRequestPolicyService = eslRequestPolicyService;
        this.etlResilienciaService = etlResilienciaService;
        this.etlEstadoIntegracaoService = etlEstadoIntegracaoService;
        this.ppgIntegrationService = ppgIntegrationService;
        this.vedacitIntegrationService = vedacitIntegrationService;
        this.seliaIntegrationService = seliaIntegrationService;
        this.supporteIntegrationService = supporteIntegrationService;
    }

    public EtlRegistroService(
            RodogarciaClient rodogarciaClient,
            EslRequestPolicyService eslRequestPolicyService,
            EtlResilienciaService etlResilienciaService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            PpgIntegrationService ppgIntegrationService,
            VedacitIntegrationService vedacitIntegrationService
    ) {
        this(
                rodogarciaClient,
                eslRequestPolicyService,
                etlResilienciaService,
                etlEstadoIntegracaoService,
                ppgIntegrationService,
                vedacitIntegrationService,
                null,
                null
        );
    }

    public EtlRegistroService(
            RodogarciaClient rodogarciaClient,
            EslRequestPolicyService eslRequestPolicyService,
            EtlResilienciaService etlResilienciaService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            PpgIntegrationService ppgIntegrationService,
            VedacitIntegrationService vedacitIntegrationService,
            SeliaIntegrationService seliaIntegrationService
    ) {
        this(
                rodogarciaClient,
                eslRequestPolicyService,
                etlResilienciaService,
                etlEstadoIntegracaoService,
                ppgIntegrationService,
                vedacitIntegrationService,
                seliaIntegrationService,
                null
        );
    }

    public ResultadoPagina processarPendenciasDestino(
            String destino,
            String headerAuth,
            ProcessadorDestino processadorDestino
    ) {
        List<LogIntegracaoModel> pendencias =
                etlEstadoIntegracaoService.buscarPendenciasCanhoto(destino, STATUS_PENDENTE_FOTO);
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

                EslOcorrenciaDTO ocorrenciaPendente = ocorrencia.get();
                ResultadoRegistro registro = etlResilienciaService.processarOcorrenciaComRetentativas(
                        destino,
                        obterChaveNfe(ocorrenciaPendente),
                        pendencia,
                        () -> processarOcorrenciaComLog(
                                destino,
                                headerAuth,
                                pendencia.getCursorNextId(),
                                ocorrenciaPendente,
                                processadorDestino,
                                pendencia
                        )
                );
                resultado = resultado.com(registro);
            } catch (EslRequestTransientException e) {
                throw e;
            } catch (Exception e) {
                etlEstadoIntegracaoService.aplicarResultadoIntegracao(pendencia, ResultadoIntegracao.erroCanhoto(
                        etlEstadoIntegracaoService.statusDadosAtualOuSucesso(pendencia),
                        e.getMessage()
                ));
                etlEstadoIntegracaoService.salvar(pendencia);
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

    public ResultadoRegistro reprocessarLogExistente(
            String destino,
            String headerAuth,
            LogIntegracaoModel logIntegracao,
            ProcessadorDestino processadorDestino
    ) {
        if (logIntegracao == null) {
            return ResultadoRegistro.ERRO;
        }

        try {
            Optional<EslOcorrenciaDTO> ocorrencia = buscarOcorrenciaPendente(headerAuth, logIntegracao);
            if (ocorrencia.isEmpty()) {
                return registrarErroRepescagem(
                        destino,
                        logIntegracao,
                        new IllegalStateException("Ocorrência não encontrada na ESL para repescagem por invoice_key")
                );
            }

            EslOcorrenciaDTO ocorrenciaRepescagem = ocorrencia.get();
            return etlResilienciaService.processarOcorrenciaComRetentativas(
                    destino,
                    obterChaveNfe(ocorrenciaRepescagem),
                    logIntegracao,
                    () -> processarOcorrenciaComLog(
                            destino,
                            headerAuth,
                            logIntegracao.getCursorNextId(),
                            ocorrenciaRepescagem,
                            processadorDestino,
                            logIntegracao
                    )
            );
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            return registrarErroRepescagem(destino, logIntegracao, e);
        }
    }

    /**
     * Reenvia exclusivamente o canhoto Vedacit de um log que já possui o XML do
     * CT-e integrado. Não consulta ocorrência por NF-e e não reenvia XML.
     */
    public ResultadoRegistro reprocessarCanhotoVedacitPorCte(LogIntegracaoModel logIntegracao) {
        return reprocessarCanhotoVedacitPorCte(logIntegracao, null);
    }

    /** Reprocessamento SFTP sem consulta ESL; a fonte é isolada pelo perfil do cliente. */
    public ResultadoRegistro reprocessarCanhotoVedacitPorCte(
            LogIntegracaoModel logIntegracao,
            VedacitSftpDocumentSource fonteSftp
    ) {
        if (!ehCandidatoCanhotoVedacit(logIntegracao)) {
            return ResultadoRegistro.IGNORADO;
        }

        EslOcorrenciaDTO ocorrencia = reconstruirOcorrenciaVedacit(logIntegracao);
        String chaveNfe = obterChaveNfe(ocorrencia);
        try {
            // No worker multi-cliente, a auditoria e o documento pertencem ao perfil atual;
            // nunca usa reconciliação global de outra fila.
            boolean reconciliacaoIsolada = fonteSftp == null && reconciliacaoSftpVedacitHabilitada;
            if (reconciliacaoIsolada
                    && etlEstadoIntegracaoService.jaExisteCanhotoVedacitEnviado(chaveNfe)) {
                logDetalheSftpVedacit.info("⏭️ [VEDACIT] NF {}: canhoto já conciliado em CT-e relacionado; evitando reenvio.", chaveNfe);
                return ResultadoRegistro.IGNORADO;
            }
            VedacitCteCanhotoReconciliationService.Decisao decisao = null;
            if (reconciliacaoIsolada) {
                if (vedacitCteCanhotoReconciliationService == null) {
                    throw new IllegalStateException("Serviço de reconciliação Vedacit indisponível");
                }
                decisao = vedacitCteCanhotoReconciliationService.reconciliar(chaveNfe, logIntegracao.getChaveCte());
                if (!decisao.encontrada()) {
                    ResultadoIntegracao pendente = ResultadoIntegracao.parcialCanhotoPendente(
                            STATUS_SUCESSO, decisao.motivo()
                    );
                    etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, pendente);
                    etlEstadoIntegracaoService.classificarCanhotoVedacit(
                            logIntegracao, ClassificacaoOperacionalCanhotoVedacit.paraPendente(decisao.motivo())
                    );
                    etlEstadoIntegracaoService.salvar(logIntegracao);
                    return ResultadoRegistro.PENDENTE_FOTO;
                }
                logIntegracao.setCanhotoChaveCteEfetiva(decisao.chaveCteEfetiva());
                logIntegracao.setCanhotoReconciliacaoTipo(decisao.tipo());
                logIntegracao.setCanhotoReconciliacaoMotivo(decisao.motivo());
                ocorrencia = comChaveCte(ocorrencia, decisao.chaveCteEfetiva());
            }
            logDetalheSftpVedacit.info(
                    "🎯 [VEDACIT] NF {}: reprocessamento cirúrgico do canhoto. CTe={}",
                    chaveNfe,
                    logIntegracao.getChaveCte()
            );
            ResultadoIntegracao resultado = fonteSftp == null
                    ? vedacitIntegrationService.processarOcorrencia(ocorrencia, null, true, false)
                    : vedacitIntegrationService.processarOcorrencia(ocorrencia, null, true, false, fonteSftp);
            etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, resultado);
            etlEstadoIntegracaoService.classificarCanhotoVedacit(
                    logIntegracao,
                    classificarResultadoCanhotoVedacit(resultado)
            );
            etlEstadoIntegracaoService.salvar(logIntegracao);
            if (decisao != null && resultado.statusCanhoto().equals(STATUS_SUCESSO)) {
                etlEstadoIntegracaoService.marcarCanhotosVedacitRelacionadosComoSucesso(
                        chaveNfe, decisao.chaveCteEfetiva(), decisao.tipo(), decisao.motivo()
                );
            }
            return etlEstadoIntegracaoService.converterResultadoRegistro(resultado);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            ResultadoIntegracao erro = ResultadoIntegracao.erroCanhoto(STATUS_SUCESSO, e.getMessage());
            etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, erro);
            etlEstadoIntegracaoService.classificarCanhotoVedacit(
                    logIntegracao, ClassificacaoOperacionalCanhotoVedacit.paraErro(e.getMessage())
            );
            etlEstadoIntegracaoService.salvar(logIntegracao);
            logDetalheSftpVedacit.error(
                    "❌ [VEDACIT] NF {}: erro no reprocessamento cirúrgico do canhoto - {}",
                    chaveNfe,
                    e.getMessage()
            );
            return ResultadoRegistro.ERRO;
        }
    }

    private EslOcorrenciaDTO comChaveCte(EslOcorrenciaDTO ocorrencia, String chaveCteEfetiva) {
        EslFreightDTO freight = ocorrencia.freight();
        EslFreightDTO freightEfetivo = new EslFreightDTO(
                freight.id(), chaveCteEfetiva, freight.orderNumber(), freight.volumeNumber()
        );
        return new EslOcorrenciaDTO(
                ocorrencia.id(), ocorrencia.orderNumber(), ocorrencia.volumeNumber(), ocorrencia.occurrenceAt(),
                ocorrencia.createdAt(), ocorrencia.invoice(), freightEfetivo, ocorrencia.occurrence()
        );
    }

    static ClassificacaoOperacionalCanhotoVedacit classificarResultadoCanhotoVedacit(
            ResultadoIntegracao resultado
    ) {
        if (resultado == null) {
            return ClassificacaoOperacionalCanhotoVedacit.PENDENTE_TECNICO;
        }
        if (STATUS_SUCESSO.equals(resultado.statusCanhoto())) {
            return ClassificacaoOperacionalCanhotoVedacit.paraSucesso();
        }
        if (STATUS_ERRO_DESTINO.equals(resultado.statusCanhoto())) {
            return ClassificacaoOperacionalCanhotoVedacit.paraErro(resultado.mensagemErroCanhoto());
        }
        return ClassificacaoOperacionalCanhotoVedacit.paraPendente(resultado.mensagemErroCanhoto());
    }

    /**
     * Reenvia somente o XML do CT-e para um erro tecnico Vedacit ja auditado.
     * A chave do CT-e vem do proprio log, evitando depender da ocorrencia ESL
     * que pode ter saído da janela historica de consulta.
     */
    public ResultadoRegistro reprocessarXmlCteVedacitPorChave(LogIntegracaoModel logIntegracao) {
        if (!ehCandidatoXmlCteVedacit(logIntegracao)) {
            return ResultadoRegistro.IGNORADO;
        }

        String chaveNfe = logIntegracao.getChaveNfe();
        try {
            ResultadoIntegracao resultado = vedacitIntegrationService.reprocessarXmlCtePorChaves(
                    chaveNfe,
                    logIntegracao.getChaveCte(),
                    logIntegracao.getStatusCanhoto()
            );
            etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, resultado);
            etlEstadoIntegracaoService.salvar(logIntegracao);
            return etlEstadoIntegracaoService.converterResultadoRegistro(resultado);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            ResultadoIntegracao erro = ResultadoIntegracao.erroDados(e.getMessage());
            etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, erro);
            etlEstadoIntegracaoService.salvar(logIntegracao);
            log.error("❌ [VEDACIT] NF {}: erro na repescagem técnica do XML - {}", chaveNfe, e.getMessage());
            return ResultadoRegistro.ERRO;
        }
    }

    public ResultadoRegistro processarOcorrencia(
            String destino,
            String headerAuth,
            Long cursorNextId,
            EslOcorrenciaDTO ocorrencia,
            ProcessadorDestino processadorDestino
    ) {
        Optional<LogIntegracaoModel> logExistente =
                etlEstadoIntegracaoService.buscarLogIntegracaoExistente(destino, ocorrencia);
        if (logExistente.isPresent()
                && etlEstadoIntegracaoService.finalizadoSemReenvio(logExistente.get())
                && !etlEstadoIntegracaoService.deveReprocessarIgnoradoSemEnvio(destino, logExistente.get())
                && (!DESTINO_VEDACIT.equals(destino)
                || etlEstadoIntegracaoService.statusSucesso(logExistente.get().getStatusCanhoto()))) {
            log.info(
                    "♻️ [{}] NF {}: Pulando (Já processada anteriormente). occurrence_id={}",
                    destino,
                    obterChaveNfe(ocorrencia),
                    obterOccurrenceId(ocorrencia)
            );
            return ResultadoRegistro.JA_PROCESSADO;
        }

        if (logExistente.isPresent() && etlResilienciaService.limiteTentativasAtingido(logExistente.get())) {
            return etlResilienciaService.resultadoErroRespeitandoLimiteTentativas(
                    destino,
                    obterChaveNfe(ocorrencia),
                    logExistente.get()
            );
        }

        LogIntegracaoModel logIntegracao = logExistente
                .orElseGet(() -> etlEstadoIntegracaoService.criarLogComStatus(
                        destino,
                        cursorNextId,
                        ocorrencia,
                        STATUS_RECEBIDO
                ));
        return etlResilienciaService.processarOcorrenciaComRetentativas(
                destino,
                obterChaveNfe(ocorrencia),
                logIntegracao,
                () -> processarOcorrenciaComLog(
                        destino,
                        headerAuth,
                        cursorNextId,
                        ocorrencia,
                        processadorDestino,
                        logIntegracao
                )
        );
    }

    public ResultadoRegistro processarEmissaoXmlVedacit(
            String headerAuth,
            Long cursorNextId,
            EslOcorrenciaDTO ocorrencia
    ) {
        Optional<LogIntegracaoModel> logExistente =
                etlEstadoIntegracaoService.buscarLogIntegracaoExistente(DESTINO_VEDACIT, ocorrencia);
        if (logExistente.isPresent() && etlEstadoIntegracaoService.statusSucesso(logExistente.get().getStatusDados())) {
            log.info(
                    "♻️ [VEDACIT] NF {}: XML do CT-e já integrado anteriormente. CTe={}",
                    obterChaveNfe(ocorrencia),
                    obterChaveCte(ocorrencia)
            );
            return ResultadoRegistro.JA_PROCESSADO;
        }

        if (logExistente.isPresent()
                && ResultadoIntegracao.STATUS_PENDENTE_ORIGEM.equals(logExistente.get().getStatusDados())
                && !sftpRodogarciaHabilitado) {
            log.info(
                    "⏸️ [VEDACIT] NF {}: XML do CT-e permanece pendente de disponibilização na ESL. CTe={}",
                    obterChaveNfe(ocorrencia),
                    obterChaveCte(ocorrencia)
            );
            return ResultadoRegistro.PENDENTE_ORIGEM;
        }

        if (logExistente.isPresent()
                && ResultadoIntegracao.STATUS_PENDENTE_ORIGEM.equals(logExistente.get().getStatusDados())) {
            log.info(
                    "📄 [VEDACIT] NF {}: nova fonte SFTP habilitada; reavaliando XML pendente. CTe={}",
                    obterChaveNfe(ocorrencia),
                    obterChaveCte(ocorrencia)
            );
        }

        if (logExistente.isPresent() && etlResilienciaService.limiteTentativasAtingido(logExistente.get())) {
            return etlResilienciaService.resultadoErroRespeitandoLimiteTentativas(
                    DESTINO_VEDACIT,
                    obterChaveNfe(ocorrencia),
                    logExistente.get()
            );
        }

        LogIntegracaoModel logIntegracao = logExistente
                .orElseGet(() -> etlEstadoIntegracaoService.criarLogComStatus(
                        DESTINO_VEDACIT,
                        cursorNextId,
                        ocorrencia,
                        STATUS_RECEBIDO
                ));
        return etlResilienciaService.processarEmissaoXmlVedacitComRetentativas(
                obterChaveNfe(ocorrencia),
                logIntegracao,
                () -> processarEmissaoXmlVedacitComLog(cursorNextId, ocorrencia, logIntegracao)
        );
    }

    private ResultadoRegistro processarEmissaoXmlVedacitComLog(
            Long cursorNextId,
            EslOcorrenciaDTO ocorrencia,
            LogIntegracaoModel logIntegracao
    ) {
        try {
            logIntegracao.setCursorNextId(cursorNextId);
            if (logIntegracao.getStatus() == null) {
                logIntegracao.setStatus(STATUS_RECEBIDO);
            }
            logIntegracao.setDataProcessamento(etlEstadoIntegracaoService.agoraAuditoria());
            etlEstadoIntegracaoService.salvar(logIntegracao);

            if (!ehCteEmitido(ocorrencia)) {
                etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, ResultadoIntegracao.ignorado());
                etlEstadoIntegracaoService.salvar(logIntegracao);
                log.info("⏭️ [VEDACIT] NF {}: XML ignorado (Código diferente de 110).", obterChaveNfe(ocorrencia));
                return ResultadoRegistro.IGNORADO;
            }

            if (vedacitIntegrationService == null) {
                throw new IllegalStateException("Servico Vedacit indisponivel para processamento do XML emitido");
            }

            ResultadoIntegracao resultado = vedacitIntegrationService.processarXmlCteEmitido(
                    ocorrencia,
                    logIntegracao.getStatusCanhoto()
            );
            etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, resultado);
            etlEstadoIntegracaoService.salvar(logIntegracao);

            ResultadoRegistro resultadoRegistro = etlEstadoIntegracaoService.converterResultadoRegistro(resultado);
            if (resultadoRegistro.erro()) {
                return etlResilienciaService.resultadoErroAposTentativa(
                        DESTINO_VEDACIT,
                        obterChaveNfe(ocorrencia),
                        logIntegracao
                );
            }
            return resultadoRegistro;
        } catch (EslRequestTransientException e) {
            throw e;
        } catch (Exception e) {
            etlEstadoIntegracaoService.aplicarResultadoIntegracao(
                    logIntegracao,
                    etlEstadoIntegracaoService.criarResultadoErroGenerico(DESTINO_VEDACIT, e)
            );
            etlEstadoIntegracaoService.salvar(logIntegracao);
            log.error("❌ [VEDACIT] NF {}: Erro ao processar XML emitido - {}", obterChaveNfe(ocorrencia), e.getMessage());
            return etlResilienciaService.resultadoErroAposTentativa(
                    DESTINO_VEDACIT,
                    obterChaveNfe(ocorrencia),
                    logIntegracao
            );
        }
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
            logIntegracao.setDataProcessamento(etlEstadoIntegracaoService.agoraAuditoria());
            etlEstadoIntegracaoService.salvar(logIntegracao);

            if (!modoTesteE2eImagem && !ocorrenciaPermitidaParaDestino(destino, ocorrencia)) {
                etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, ResultadoIntegracao.ignorado());
                etlEstadoIntegracaoService.salvar(logIntegracao);

                log.info("⏭️ [{}] NF {}: Ignorada (código ESL sem tratamento para o destino).", destino, obterChaveNfe(ocorrencia));
                return ResultadoRegistro.IGNORADO;
            }

            if (modoTesteE2eImagem && !ocorrenciaPermitidaParaDestino(destino, ocorrencia)) {
                log.warn(
                        "🧪 [{}] NF {}: Modo E2E ativo, filtro occurrence.code == 1 bypassado. codigo_origem={}",
                        destino,
                        obterChaveNfe(ocorrencia),
                        ocorrencia != null && ocorrencia.occurrence() != null ? ocorrencia.occurrence().code() : null
                );
            }

            if (!notaFiscalPermitidaPorDestino(destino, ocorrencia)) {
                etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, ResultadoIntegracao.ignorado());
                etlEstadoIntegracaoService.salvar(logIntegracao);
                return ResultadoRegistro.IGNORADO;
            }

            boolean comprovanteObrigatorio = comprovanteObrigatorio(destino, ocorrencia);
            boolean vedacitComprovanteComFontePrioritaria = DESTINO_VEDACIT.equals(destino) && comprovanteObrigatorio;
            ResultadoBuscaComprovante buscaComprovante = comprovanteObrigatorio && !vedacitComprovanteComFontePrioritaria
                    ? buscarComprovanteEntregaOpcional(destino, headerAuth, ocorrencia)
                    : ResultadoBuscaComprovante.semComprovante(null);
            ComprovanteEslDTO comprovante = buscaComprovante.comprovante();

            String chave = obterChaveNfe(ocorrencia);
            ComprovanteEslDTO comprovanteProcessamento = prepararComprovanteParaModoTeste(comprovante, chave, destino);
            if (!comprovanteTemUrlImagem(comprovanteProcessamento)) {
                comprovanteProcessamento = null;
            }

            if (comprovanteObrigatorio && comprovanteProcessamento == null && !vedacitComprovanteComFontePrioritaria) {
                String motivoPendente = normalizarMotivoCanhotoIndisponivel(buscaComprovante.motivoIndisponivel());
                ResultadoIntegracao resultadoPendente = ResultadoIntegracao.pendenteFotoObrigatorio(motivoPendente);
                etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, resultadoPendente);
                etlEstadoIntegracaoService.salvar(logIntegracao);

                log.warn("⏳ [{}] NF {}: {}. Payload não enviado.", destino, chave, motivoPendente);
                return ResultadoRegistro.PENDENTE_FOTO;
            }

            if (comprovanteProcessamento != null) {
                log.info("⬇️ [{}] NF {}: Baixando imagem do canhoto...", destino, chave);
            }

            ResultadoIntegracao resultadoProcessador;
            try {
                resultadoProcessador = processadorDestino.processar(ocorrencia, comprovanteProcessamento, logIntegracao);
            } catch (EslRequestTransientException e) {
                throw e;
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

            etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, resultadoProcessador);
            etlEstadoIntegracaoService.salvar(logIntegracao);

            ResultadoRegistro resultadoRegistro = etlEstadoIntegracaoService.converterResultadoRegistro(resultadoProcessador);
            if (resultadoRegistro == ResultadoRegistro.IGNORADO) {
                log.info("⏭️ [{}] NF {}: Ignorada pelo destino.", destino, chave);
                return ResultadoRegistro.IGNORADO;
            }

            if (resultadoRegistro == ResultadoRegistro.PENDENTE_FOTO) {
                log.info("⏳ [{}] NF {}: Aguardando canhoto para concluir o destino.", destino, chave);
                return ResultadoRegistro.PENDENTE_FOTO;
            }

            if (resultadoRegistro.erro()) {
                log.error("❌ [{}] NF {}: Destino retornou erro controlado.", destino, chave);
                return etlResilienciaService.resultadoErroAposTentativa(
                        destino,
                        chave,
                        logIntegracao
                );
            }

            log.info("✅ [{}] NF {}: Processamento do destino concluído com sucesso!", destino, chave);
            return ResultadoRegistro.ENVIADO;
        } catch (EslRequestTransientException e) {
            throw e;
        } catch (FalhaConsultaComprovanteException e) {
            etlEstadoIntegracaoService.aplicarResultadoIntegracao(
                    logIntegracao,
                    ResultadoIntegracao.erroCanhoto(
                            etlEstadoIntegracaoService.statusDadosAtualOuSucesso(logIntegracao),
                            e.getMessage()
                    )
            );
            etlEstadoIntegracaoService.salvar(logIntegracao);

            log.error("❌ [{}] NF {}: Erro ao consultar comprovante - {}", destino, obterChaveNfe(ocorrencia), e.getMessage());
            return etlResilienciaService.resultadoErroAposTentativa(
                    destino,
                    obterChaveNfe(ocorrencia),
                    logIntegracao
            );
        } catch (Exception e) {
            etlEstadoIntegracaoService.aplicarResultadoIntegracao(
                    logIntegracao,
                    etlEstadoIntegracaoService.criarResultadoErroGenerico(destino, e)
            );
            etlEstadoIntegracaoService.salvar(logIntegracao);

            log.error("❌ [{}] NF {}: Erro ao processar - {}", destino, obterChaveNfe(ocorrencia), e.getMessage());
            return etlResilienciaService.resultadoErroAposTentativa(
                    destino,
                    obterChaveNfe(ocorrencia),
                    logIntegracao
            );
        }
    }

    private Optional<EslOcorrenciaDTO> buscarOcorrenciaPendente(String headerAuth, LogIntegracaoModel pendencia) {
        String chaveNfe = pendencia.getChaveNfe();
        if (chaveNfe == null || chaveNfe.isBlank()) {
            return Optional.empty();
        }

        EslLoteResponseDTO lote = eslRequestPolicyService.executarComTelemetria(
                EslRequestContext.criar(pendencia.getSistemaDestino(), "OCCURRENCE_BY_INVOICE"),
                () -> rodogarciaClient.buscarOcorrencias(
                        headerAuth,
                        null,
                        chaveNfe,
                        null,
                        CODIGO_ENTREGA_REALIZADA
                )
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

    private ResultadoRegistro registrarErroRepescagem(
            String destino,
            LogIntegracaoModel logIntegracao,
            Exception erro
    ) {
        ResultadoIntegracao resultado = ehErroParcialCanhoto(logIntegracao)
                ? ResultadoIntegracao.erroCanhoto(
                        etlEstadoIntegracaoService.statusDadosAtualOuSucesso(logIntegracao),
                        erro.getMessage()
                )
                : etlEstadoIntegracaoService.criarResultadoErroGenerico(destino, erro);
        etlEstadoIntegracaoService.aplicarResultadoIntegracao(
                logIntegracao,
                resultado
        );
        etlEstadoIntegracaoService.salvar(logIntegracao);

        log.error(
                "❌ [DESTINO: {}] NF {}: Erro durante repescagem - {}",
                destino,
                logIntegracao.getChaveNfe(),
                erro.getMessage()
        );
        return etlResilienciaService.resultadoErroAposTentativa(
                destino,
                logIntegracao.getChaveNfe(),
                logIntegracao
        );
    }

    private boolean ehErroParcialCanhoto(LogIntegracaoModel logIntegracao) {
        return logIntegracao != null
                && STATUS_ERRO_DESTINO.equals(logIntegracao.getStatus())
                && STATUS_SUCESSO.equals(logIntegracao.getStatusDados())
                && STATUS_ERRO_DESTINO.equals(logIntegracao.getStatusCanhoto());
    }

    private boolean ehCandidatoCanhotoVedacit(LogIntegracaoModel logIntegracao) {
        return logIntegracao != null
                && DESTINO_VEDACIT.equals(logIntegracao.getSistemaDestino())
                && STATUS_SUCESSO.equals(logIntegracao.getStatusDados())
                && (STATUS_ERRO_DESTINO.equals(logIntegracao.getStatusCanhoto())
                        || ResultadoIntegracao.STATUS_PENDENTE_FOTO.equals(logIntegracao.getStatusCanhoto())
                        || ResultadoIntegracao.STATUS_NAO_APLICAVEL.equals(logIntegracao.getStatusCanhoto()))
                && logIntegracao.getChaveNfe() != null
                && logIntegracao.getChaveNfe().length() == 44
                && logIntegracao.getChaveCte() != null
                && !logIntegracao.getChaveCte().isBlank();
    }

    private boolean ehCandidatoXmlCteVedacit(LogIntegracaoModel logIntegracao) {
        return logIntegracao != null
                && DESTINO_VEDACIT.equals(logIntegracao.getSistemaDestino())
                && STATUS_ERRO_DESTINO.equals(logIntegracao.getStatus())
                && STATUS_ERRO_DESTINO.equals(logIntegracao.getStatusDados())
                && logIntegracao.getChaveNfe() != null
                && logIntegracao.getChaveNfe().length() == 44
                && logIntegracao.getChaveCte() != null
                && !logIntegracao.getChaveCte().isBlank();
    }

    private EslOcorrenciaDTO reconstruirOcorrenciaVedacit(LogIntegracaoModel logIntegracao) {
        String chaveNfe = logIntegracao.getChaveNfe();
        LocalDateTime dataAuditoria = logIntegracao.getDataProcessamentoDados();
        if (dataAuditoria == null) {
            dataAuditoria = logIntegracao.getDataProcessamento();
        }
        if (dataAuditoria == null) {
            dataAuditoria = etlEstadoIntegracaoService.agoraAuditoria();
        }
        OffsetDateTime dataEntrega = dataAuditoria.atZone(ZoneId.of("America/Sao_Paulo")).toOffsetDateTime();

        return new EslOcorrenciaDTO(
                logIntegracao.getOccurrenceId(),
                logIntegracao.getOrderNumber(),
                logIntegracao.getVolumeNumber(),
                dataEntrega,
                dataEntrega,
                new EslInvoiceDTO(null, chaveNfe, chaveNfe.substring(22, 25), chaveNfe.substring(25, 34)),
                new EslFreightDTO(
                        logIntegracao.getFreightId(),
                        logIntegracao.getChaveCte(),
                        logIntegracao.getOrderNumber(),
                        logIntegracao.getVolumeNumber()
                ),
                new EslOccurrenceDefDTO(null, CODIGO_ENTREGA_REALIZADA, "Entrega Realizada")
        );
    }

    private void manterPendenteSemOcorrencia(LogIntegracaoModel pendencia) {
        pendencia.setMensagemErroCanhoto("Ocorrência não encontrada na ESL para retry por invoice_key");
        pendencia.setStatusCanhoto(STATUS_PENDENTE_FOTO);
        if (pendencia.getStatus() == null || STATUS_RECEBIDO.equals(pendencia.getStatus())) {
            pendencia.setStatus(STATUS_PENDENTE_FOTO);
        }
        pendencia.setDataProcessamento(etlEstadoIntegracaoService.agoraAuditoria());
        etlEstadoIntegracaoService.salvar(pendencia);
    }

    private ResultadoBuscaComprovante buscarComprovanteEntregaOpcional(
            String destino,
            String headerAuth,
            EslOcorrenciaDTO ocorrencia
    ) {
        String cteKey = obterChaveCte(ocorrencia);

        if (cteKey == null || cteKey.isBlank()) {
            log.warn(
                    "⏭️ NF {}: Chave CTe ausente na ocorrência ESL; busca do comprovante pulada.",
                    obterChaveNfe(ocorrencia)
            );
            return ResultadoBuscaComprovante.semComprovante(MOTIVO_CTE_AUSENTE);
        }

        ComprovanteEslDTO comprovante;
        try {
            comprovante = eslRequestPolicyService.executarComTelemetria(
                    EslRequestContext.criar(destino, "DELIVERY_RECEIPT"),
                    () -> rodogarciaClient.buscarComprovante(obterHeaderComprovante(destino, headerAuth), cteKey)
            );
        } catch (EslRequestTransientException e) {
            throw new FalhaConsultaComprovanteException(
                    "Falha transitória da ESL ao consultar comprovante: " + e.getMessage(),
                    e
            );
        }

        if (comprovante == null || comprovante.data() == null || comprovante.data().isEmpty()) {
            return ResultadoBuscaComprovante.semComprovante(MOTIVO_CANHOTO_INDISPONIVEL);
        }

        return ResultadoBuscaComprovante.encontrado(comprovante);
    }

    String obterHeaderComprovante(String destino, String headerAuth) {
        if (DESTINO_SELIA.equals(destino)
                && tokenSeliaComprovanteEsl != null
                && !tokenSeliaComprovanteEsl.isBlank()) {
            return "Bearer " + tokenSeliaComprovanteEsl.trim();
        }

        if (DESTINO_SUPPORTE.equals(destino)
                && tokenSupporteComprovanteEsl != null
                && !tokenSupporteComprovanteEsl.isBlank()) {
            return "Bearer " + tokenSupporteComprovanteEsl.trim();
        }

        if (DESTINO_VEDACIT.equals(destino)
                && tokenVedacitComprovanteEsl != null
                && !tokenVedacitComprovanteEsl.isBlank()) {
            return "Bearer " + tokenVedacitComprovanteEsl.trim();
        }

        if (DESTINO_VEDACIT.equals(destino)
                && tokenMasterEsl != null
                && !tokenMasterEsl.isBlank()) {
            return "Bearer " + tokenMasterEsl.trim();
        }

        return headerAuth;
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

    private String normalizarMotivoCanhotoIndisponivel(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            return MOTIVO_CANHOTO_INDISPONIVEL;
        }

        return motivo;
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

    public boolean ehEntregaRealizada(EslOcorrenciaDTO ocorrencia) {
        return ocorrencia != null
                && ocorrencia.occurrence() != null
                && ocorrencia.occurrence().code() != null
                && ocorrencia.occurrence().code() == CODIGO_ENTREGA_REALIZADA;
    }

    boolean ehCteEmitido(EslOcorrenciaDTO ocorrencia) {
        return ocorrencia != null
                && ocorrencia.occurrence() != null
                && ocorrencia.occurrence().code() != null
                && ocorrencia.occurrence().code() == EtapaVedacit.EMISSAO_XML.codigoOcorrencia();
    }

    public Long obterOccurrenceId(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia == null) {
            return null;
        }

        return ocorrencia.id();
    }

    public String obterChaveNfe(EslOcorrenciaDTO ocorrencia) {
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

    private boolean notaFiscalPermitidaPorDestino(String destino, EslOcorrenciaDTO ocorrencia) {
        if (DESTINO_PPG.equals(destino)) {
            return ppgIntegrationService.notaFiscalPermitida(ocorrencia);
        }

        if (DESTINO_VEDACIT.equals(destino)) {
            return vedacitIntegrationService.notaFiscalPermitida(ocorrencia);
        }

        if (DESTINO_SELIA.equals(destino)) {
            return seliaIntegrationService != null && seliaIntegrationService.notaFiscalPermitida(ocorrencia);
        }

        if (DESTINO_SUPPORTE.equals(destino)) {
            return supporteIntegrationService != null && supporteIntegrationService.notaFiscalPermitida(ocorrencia);
        }

        return true;
    }

    private boolean ocorrenciaPermitidaParaDestino(String destino, EslOcorrenciaDTO ocorrencia) {
        if (DESTINO_SELIA.equals(destino)) {
            return seliaIntegrationService != null && seliaIntegrationService.ocorrenciaAceita(ocorrencia);
        }
        return ehEntregaRealizada(ocorrencia);
    }

    private boolean comprovanteObrigatorio(String destino, EslOcorrenciaDTO ocorrencia) {
        if (DESTINO_SELIA.equals(destino)) {
            return seliaIntegrationService != null && seliaIntegrationService.exigeComprovante(ocorrencia);
        }
        return DESTINO_PPG.equals(destino);
    }

    boolean modoTesteE2eImagemAtivo() {
        return modoTesteE2eImagem;
    }

    private boolean loteVazio(EslLoteResponseDTO lote) {
        return lote == null || lote.data() == null || lote.data().isEmpty();
    }

    private record ResultadoBuscaComprovante(ComprovanteEslDTO comprovante, String motivoIndisponivel) {
        static ResultadoBuscaComprovante encontrado(ComprovanteEslDTO comprovante) {
            return new ResultadoBuscaComprovante(comprovante, null);
        }

        static ResultadoBuscaComprovante semComprovante(String motivoIndisponivel) {
            return new ResultadoBuscaComprovante(null, motivoIndisponivel);
        }
    }

    private static class FalhaConsultaComprovanteException extends RuntimeException {
        private FalhaConsultaComprovanteException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
