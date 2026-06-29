package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslItemDTO;
import com.example.satelite.dto.rodogarcia.EslLoteResponseDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.services.etl.EslRequestPolicyService.EslRequestTransientException;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

@Service
public class EtlRegistroService {

    private static final Logger log = LoggerFactory.getLogger(EtlRegistroService.class);

    private static final int CODIGO_ENTREGA_REALIZADA = 1;
    private static final String DESTINO_PPG = "PPG";
    private static final String DESTINO_VEDACIT = "VEDACIT";
    private static final String STATUS_RECEBIDO = ResultadoIntegracao.STATUS_RECEBIDO;
    private static final String STATUS_PENDENTE_FOTO = ResultadoIntegracao.STATUS_PENDENTE_FOTO;
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

    @Value("${APP_E2E_IMAGE_TEST_MODE:false}")
    private boolean modoTesteE2eImagem;

    @Value("${APP_E2E_TEST_IMAGE_URL:" + URL_IMAGEM_TESTE_PADRAO + "}")
    private String urlImagemTesteE2e;

    public EtlRegistroService(
            RodogarciaClient rodogarciaClient,
            EslRequestPolicyService eslRequestPolicyService,
            EtlResilienciaService etlResilienciaService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            PpgIntegrationService ppgIntegrationService,
            VedacitIntegrationService vedacitIntegrationService
    ) {
        this.rodogarciaClient = rodogarciaClient;
        this.eslRequestPolicyService = eslRequestPolicyService;
        this.etlResilienciaService = etlResilienciaService;
        this.etlEstadoIntegracaoService = etlEstadoIntegracaoService;
        this.ppgIntegrationService = ppgIntegrationService;
        this.vedacitIntegrationService = vedacitIntegrationService;
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

    public ResultadoRegistro processarOcorrencia(
            String destino,
            String headerAuth,
            Long cursorNextId,
            EslOcorrenciaDTO ocorrencia,
            ProcessadorDestino processadorDestino
    ) {
        Optional<LogIntegracaoModel> logExistente =
                etlEstadoIntegracaoService.buscarLogIntegracaoExistente(destino, ocorrencia);
        if (logExistente.isPresent() && etlEstadoIntegracaoService.finalizadoSemReenvio(logExistente.get())) {
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
            etlEstadoIntegracaoService.salvar(logIntegracao);

            if (!modoTesteE2eImagem && !ehEntregaRealizada(ocorrencia)) {
                etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, ResultadoIntegracao.ignorado());
                etlEstadoIntegracaoService.salvar(logIntegracao);

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
                etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, ResultadoIntegracao.ignorado());
                etlEstadoIntegracaoService.salvar(logIntegracao);
                return ResultadoRegistro.IGNORADO;
            }

            log.info("📄 [{}] NF {}: Buscando comprovante de entrega na ESL...", destino, obterChaveNfe(ocorrencia));
            ResultadoBuscaComprovante buscaComprovante = buscarComprovanteEntregaOpcional(headerAuth, ocorrencia);
            ComprovanteEslDTO comprovante = buscaComprovante.comprovante();

            String chave = obterChaveNfe(ocorrencia);
            ComprovanteEslDTO comprovanteProcessamento = prepararComprovanteParaModoTeste(comprovante, chave, destino);
            if (!comprovanteTemUrlImagem(comprovanteProcessamento)) {
                comprovanteProcessamento = null;
            }

            if (DESTINO_PPG.equals(destino) && comprovanteProcessamento == null) {
                String motivoPendente = normalizarMotivoCanhotoIndisponivel(buscaComprovante.motivoIndisponivel());
                ResultadoIntegracao resultadoPendente = ResultadoIntegracao.pendenteFotoPpg(motivoPendente);
                etlEstadoIntegracaoService.aplicarResultadoIntegracao(logIntegracao, resultadoPendente);
                etlEstadoIntegracaoService.salvar(logIntegracao);

                log.warn("⏳ [PPG] NF {}: {}. Payload não enviado.", chave, motivoPendente);
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

        EslLoteResponseDTO lote = eslRequestPolicyService.executar(
                "buscarOcorrenciaPendente invoice_key=" + chaveNfe,
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

    private void manterPendenteSemOcorrencia(LogIntegracaoModel pendencia) {
        pendencia.setMensagemErroCanhoto("Ocorrência não encontrada na ESL para retry por invoice_key");
        pendencia.setStatusCanhoto(STATUS_PENDENTE_FOTO);
        if (pendencia.getStatus() == null || STATUS_RECEBIDO.equals(pendencia.getStatus())) {
            pendencia.setStatus(STATUS_PENDENTE_FOTO);
        }
        pendencia.setDataProcessamento(LocalDateTime.now());
        etlEstadoIntegracaoService.salvar(pendencia);
    }

    private ResultadoBuscaComprovante buscarComprovanteEntregaOpcional(String headerAuth, EslOcorrenciaDTO ocorrencia) {
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
            comprovante = eslRequestPolicyService.executar(
                    "buscarComprovante cte_key=" + cteKey,
                    () -> rodogarciaClient.buscarComprovante(headerAuth, cteKey)
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

        return true;
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
