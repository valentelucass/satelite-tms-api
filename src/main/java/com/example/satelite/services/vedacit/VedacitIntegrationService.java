package com.example.satelite.services.vedacit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.config.VedacitTokenHeaderHandler;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslItemDTO;
import com.example.satelite.dto.rodogarcia.CteDataDTO;
import com.example.satelite.dto.rodogarcia.CteItemDTO;
import com.example.satelite.dto.rodogarcia.CteResponseDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.services.etl.EslRequestPolicyService;
import com.example.satelite.services.etl.EslRequestContext;
import com.example.satelite.services.etl.EslRequestPolicyService.EslRequestTransientException;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocumentSource;
import com.example.satelite.utils.ImageDownloader;
import com.example.satelite.utils.ImageUtils;
import com.example.satelite.vedacit.cte.CTe;
import com.example.satelite.vedacit.cte.ICTe;
import com.example.satelite.vedacit.cte.sgt.RetornoOfstring;
import com.example.satelite.vedacit.nfe.Canhoto;
import com.example.satelite.vedacit.nfe.INFe;
import com.example.satelite.vedacit.nfe.NFe;
import com.example.satelite.vedacit.nfe.RetornoOfboolean;

import jakarta.xml.ws.Binding;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.handler.Handler;
import jakarta.xml.ws.soap.SOAPFaultException;

import org.datacontract.schemas._2004._07.dominio_objetosdevalor_embarcador.ObjectFactory;
import org.datacontract.schemas._2004._07.dominio_objetosdevalor_embarcador.Ocorrencia;
import org.datacontract.schemas._2004._07.dominio_objetosdevalor_embarcador.TipoOcorrencia;
import org.datacontract.schemas._2004._07.sgt.RetornoOfint;
import org.tempuri.IOcorrencias;
import org.tempuri.Ocorrencias;

import java.util.ArrayList;
import java.util.Arrays;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
public class VedacitIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(VedacitIntegrationService.class);
    private static final String OBSERVACAO_ENTREGA = "Entrega Realizada";
    private static final String OBSERVACAO_CANHOTO = "Canhoto integrado pelo Satelite TMS";
    private static final String WSDL_OCORRENCIAS = "/wsdl/vedacit/ocorrencias/Ocorrencias.wsdl";
    private static final String WSDL_NFE = "/wsdl/vedacit/nfe/NFe.wsdl";
    private static final String WSDL_CTE = "/wsdl/vedacit/cte/CTe.wsdl";

    @Value("${VEDACIT_API_TOKEN}")
    private String vedacitToken;

    @Value("${VEDACIT_API_BASE_URL}")
    private String vedacitApiBaseUrl;

    @Value("${RODOGARCIA_MASTER_API_REST:}")
    private String tokenCteXmlEsl;

    @Value("${RODOGARCIA_TOKEN_VEDACIT_COMPROVANTE:}")
    private String tokenComprovanteEsl;

    @Value("${RODOGARCIA_TOKEN_VEDACIT:}")
    private String tokenVedacitEsl;

    @Value("${VEDACIT_SEND_OCCURRENCE_ENABLED:true}")
    private boolean envioOcorrenciaHabilitado;

    @Value("${VEDACIT_SEND_CANHOTO_ENABLED:true}")
    private boolean envioCanhotoHabilitado;

    @Value("${VEDACIT_SEND_CTE_XML_ENABLED:false}")
    private boolean envioXmlCteHabilitado;

    @Value("${VEDACIT_SFTP_RECEIPT_ONLY:false}")
    private boolean canhotoExclusivamenteSftp;

    @Value("${VEDACIT_NFE_WHITELIST:}")
    private String nfeWhitelist;

    @Value("${VEDACIT_NFE_WHITELIST_ENABLED:true}")
    private boolean whitelistEnabled;

    @Value("${VEDACIT_SOAP_CONNECT_TIMEOUT_MS:30000}")
    private int soapConnectTimeoutMs;

    @Value("${VEDACIT_SOAP_READ_TIMEOUT_MS:180000}")
    private int soapReadTimeoutMs;

    @Value("${VEDACIT_SOAP_INVOCATION_TIMEOUT_MS:210000}")
    private int soapInvocationTimeoutMs;

    private final ImageDownloader imageDownloader;
    private final RodogarciaClient rodogarciaClient;
    private final EslRequestPolicyService eslRequestPolicyService;
    private final VedacitSftpDocumentSource vedacitSftpDocumentSource;

    public VedacitIntegrationService(
            ImageDownloader imageDownloader,
            RodogarciaClient rodogarciaClient,
            EslRequestPolicyService eslRequestPolicyService
    ) {
        this(imageDownloader, rodogarciaClient, eslRequestPolicyService, null);
    }

    @Autowired
    public VedacitIntegrationService(
            ImageDownloader imageDownloader,
            RodogarciaClient rodogarciaClient,
            EslRequestPolicyService eslRequestPolicyService,
            VedacitSftpDocumentSource vedacitSftpDocumentSource
    ) {
        this.imageDownloader = imageDownloader;
        this.rodogarciaClient = rodogarciaClient;
        this.eslRequestPolicyService = eslRequestPolicyService;
        this.vedacitSftpDocumentSource = vedacitSftpDocumentSource;
    }

    public ResultadoIntegracao processarOcorrencia(EslOcorrenciaDTO ocorrencia, ComprovanteEslDTO comprovante) {
        return processarOcorrencia(ocorrencia, comprovante, false, false);
    }

    /**
     * Integra somente o XML no momento em que a ESL registra a emissao do CT-e.
     * O comprovante de entrega pertence a outro fluxo e nao pode bloquear esta etapa.
     */
    public ResultadoIntegracao processarXmlCteEmitido(
            EslOcorrenciaDTO ocorrencia,
            String statusCanhotoAtual
    ) {
        String chaveNfe = obterChaveNfeLog(ocorrencia);
        String cteKey = obterChaveCteLog(ocorrencia);
        String statusCanhoto = statusCanhotoAtual == null
                || statusCanhotoAtual.isBlank()
                || ResultadoIntegracao.STATUS_RECEBIDO.equals(statusCanhotoAtual)
                ? ResultadoIntegracao.STATUS_NAO_APLICAVEL
                : statusCanhotoAtual;

        if (!notaFiscalPermitida(ocorrencia)) {
            log.warn("⚠️ [VEDACIT] NF {} ignorada por não estar na Whitelist de Produção", chaveNfe);
            return ResultadoIntegracao.ignorado();
        }

        if (!envioXmlCteHabilitado) {
            log.info("⏭️ [VEDACIT] NF {}: Envio de XML do CT-e desabilitado por feature toggle.", chaveNfe);
            return ResultadoIntegracao.vedacitConcluido(ResultadoIntegracao.STATUS_NAO_APLICAVEL, statusCanhoto);
        }

        if (chaveCteAusente(ocorrencia)) {
            String mensagem = "Chave CTe ausente para envio do XML CT-e";
            log.warn("⏭️ [VEDACIT] NF {}: {}. Requisição do XML não executada.", chaveNfe, mensagem);
            return ResultadoIntegracao.erroDados(mensagem);
        }

        try {
            byte[] xmlCte = baixarXmlCte(ocorrencia, chaveNfe);
            enviarXmlCte(xmlCte, chaveNfe, cteKey);
            return ResultadoIntegracao.vedacitConcluido(ResultadoIntegracao.STATUS_SUCESSO, statusCanhoto);
        } catch (XmlCteIndisponivelNaOrigemException e) {
            log.warn("⏸️ [VEDACIT] NF {}: XML do CT-e indisponível na ESL. CTe={}", chaveNfe, cteKey);
            return ResultadoIntegracao.pendenteOrigemDados(statusCanhoto, e.getMessage());
        } catch (EslRequestTransientException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            log.error("❌ [VEDACIT] NF {}: Erro ao processar XML emitido - {}. CTe={}", chaveNfe, e.getMessage(), cteKey);
            return ResultadoIntegracao.erroDados(e.getMessage());
        }
    }

    /**
     * Recupera somente o XML de um CT-e que ja possui auditoria Vedacit.
     * Nao consulta ocorrencia historica, nao envia ocorrencia de entrega e nao
     * toca no canhoto existente.
     */
    public ResultadoIntegracao reprocessarXmlCtePorChaves(
            String chaveNfe,
            String chaveCte,
            String statusCanhotoAtual
    ) {
        String chaveNfeNormalizada = textoObrigatorio(chaveNfe, "Chave NF-e ausente para repescagem do XML");
        String chaveCteNormalizada = textoObrigatorio(chaveCte, "Chave CTe ausente para repescagem do XML");
        String statusCanhoto = statusCanhotoAtual == null || statusCanhotoAtual.isBlank()
                ? ResultadoIntegracao.STATUS_NAO_APLICAVEL
                : statusCanhotoAtual;

        if (!envioXmlCteHabilitado) {
            return ResultadoIntegracao.erroDados("Envio de XML do CT-e desabilitado por feature toggle");
        }

        try {
            log.info("🌙 [VEDACIT] NF {}: repescagem técnica do XML por CT-e={}", chaveNfeNormalizada, chaveCteNormalizada);
            byte[] xmlCte = baixarXmlCte(chaveCteNormalizada, chaveNfeNormalizada);
            enviarXmlCte(xmlCte, chaveNfeNormalizada, chaveCteNormalizada);
            return ResultadoIntegracao.vedacitConcluido(ResultadoIntegracao.STATUS_SUCESSO, statusCanhoto);
        } catch (XmlCteIndisponivelNaOrigemException e) {
            log.warn("⏸️ [VEDACIT] NF {}: XML do CT-e indisponível na ESL. CTe={}", chaveNfeNormalizada, chaveCteNormalizada);
            return ResultadoIntegracao.pendenteOrigemDados(statusCanhoto, e.getMessage());
        } catch (EslRequestTransientException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("❌ [VEDACIT] NF {}: erro na repescagem técnica do XML - {}. CTe={}",
                    chaveNfeNormalizada, e.getMessage(), chaveCteNormalizada);
            return ResultadoIntegracao.erroDados(e.getMessage());
        }
    }

    public ResultadoIntegracao processarOcorrencia(
            EslOcorrenciaDTO ocorrencia,
            ComprovanteEslDTO comprovante,
            boolean dadosJaEnviados,
            boolean canhotoJaEnviado
    ) {
        String chaveNfe = obterChaveNfeLog(ocorrencia);
        String cteKey = obterChaveCteLog(ocorrencia);

        if (!notaFiscalPermitida(ocorrencia)) {
            log.warn("⚠️ [VEDACIT] NF {} ignorada por não estar na Whitelist de Produção", chaveNfe);
            return ResultadoIntegracao.ignorado();
        }

        if (!algumEnvioHabilitado()) {
            log.warn("⚠️ [VEDACIT] NF {}: Todos os subfluxos estão desabilitados por feature toggle. CTe={}", chaveNfe, cteKey);
            return ResultadoIntegracao.ignorado();
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        String dataOcorrencia = ocorrencia.occurrenceAt().format(formatter);
        String statusDados = dadosJaEnviados
                ? ResultadoIntegracao.STATUS_SUCESSO
                : ResultadoIntegracao.STATUS_NAO_APLICAVEL;
        String statusCanhoto = canhotoJaEnviado
                ? ResultadoIntegracao.STATUS_SUCESSO
                : ResultadoIntegracao.STATUS_NAO_APLICAVEL;

        try {
            if (dadosJaEnviados) {
                log.info("⏭️ [VEDACIT] NF {}: Dados/XML já enviados anteriormente. Pulando etapa.", chaveNfe);
            } else {
                boolean fluxoDadosHabilitado = false;

                if (envioOcorrenciaHabilitado) {
                    fluxoDadosHabilitado = true;
                    enviarOcorrencia(ocorrencia, dataOcorrencia, chaveNfe, cteKey);
                } else {
                    log.info("⏭️ [VEDACIT] NF {}: Envio de ocorrência desabilitado por feature toggle.", chaveNfe);
                }

                if (envioXmlCteHabilitado) {
                    if (chaveCteAusente(ocorrencia)) {
                        String mensagem = "Chave CTe ausente para envio do XML CT-e";
                        log.warn("⏭️ [VEDACIT] NF {}: {}. Requisição do XML não executada.", chaveNfe, mensagem);
                        return ResultadoIntegracao.erroDados(mensagem);
                    }

                    fluxoDadosHabilitado = true;
                    byte[] xmlCte = baixarXmlCte(ocorrencia, chaveNfe);
                    enviarXmlCte(xmlCte, chaveNfe, cteKey);
                }

                statusDados = fluxoDadosHabilitado
                        ? ResultadoIntegracao.STATUS_SUCESSO
                        : ResultadoIntegracao.STATUS_NAO_APLICAVEL;
            }
        } catch (EslRequestTransientException e) {
            throw e;
        } catch (XmlCteIndisponivelNaOrigemException e) {
            log.warn("⏸️ [VEDACIT] NF {}: XML do CT-e indisponível na ESL. CTe={}", chaveNfe, cteKey);
            return ResultadoIntegracao.pendenteOrigemDados(statusCanhoto, e.getMessage());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            log.error("❌ [VEDACIT] NF {}: Erro ao processar dados/XML - {}. CTe={}", chaveNfe, e.getMessage(), cteKey);
            return ResultadoIntegracao.erroDados(e.getMessage());
        }

        if (canhotoJaEnviado) {
            log.info("⏭️ [VEDACIT] NF {}: Canhoto já enviado anteriormente. Pulando etapa.", chaveNfe);
            return ResultadoIntegracao.vedacitConcluido(statusDados, ResultadoIntegracao.STATUS_SUCESSO);
        }

        if (!envioCanhotoHabilitado) {
            log.info("⏭️ [VEDACIT] NF {}: Envio de canhoto desabilitado por feature toggle.", chaveNfe);
            return ResultadoIntegracao.vedacitConcluido(statusDados, ResultadoIntegracao.STATUS_NAO_APLICAVEL);
        }

        try {
            Canhoto canhoto = converterParaCanhoto(ocorrencia, comprovante, dataOcorrencia, formatter);
            enviarCanhoto(canhoto, chaveNfe, cteKey);
            statusCanhoto = ResultadoIntegracao.STATUS_SUCESSO;
            return ResultadoIntegracao.vedacitConcluido(statusDados, statusCanhoto);
        } catch (CanhotoIndisponivelNaOrigemException e) {
            String origemConsultada = canhotoExclusivamenteSftp
                    ? "no SFTP (lote exclusivo; ESL não consultada)"
                    : "no SFTP e na ESL";
            log.warn("⏳ [VEDACIT] NF {}: Canhoto indisponível {}. CTe={}", chaveNfe, origemConsultada, cteKey);
            return ResultadoIntegracao.parcialCanhotoPendente(statusDados, e.getMessage());
        } catch (EslRequestTransientException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            log.error("❌ [VEDACIT] NF {}: Erro ao processar canhoto - {}. CTe={}", chaveNfe, e.getMessage(), cteKey);
            return ResultadoIntegracao.erroCanhoto(statusDados, e.getMessage());
        }
    }

    public boolean notaFiscalPermitida(EslOcorrenciaDTO ocorrencia) {
        if (!whitelistEnabled) {
            return true;
        }

        return obterWhitelistNfe().contains(obterChaveNfeLog(ocorrencia));
    }

    private boolean algumEnvioHabilitado() {
        return envioOcorrenciaHabilitado || envioCanhotoHabilitado || envioXmlCteHabilitado;
    }

    private boolean comprovanteTemImagem(ComprovanteEslDTO comprovante) {
        if (comprovante == null || comprovante.data() == null || comprovante.data().isEmpty()) {
            return false;
        }

        ComprovanteEslItemDTO primeiroComprovante = comprovante.data().get(0);
        return primeiroComprovante != null
                && primeiroComprovante.imageUrl() != null
                && !primeiroComprovante.imageUrl().isBlank();
    }

    private String montarEndpointOcorrencias() {
        return montarEndpoint("Ocorrencias.svc");
    }

    private String montarEndpointNFe() {
        return montarEndpoint("NFe.svc");
    }

    private String montarEndpointCte() {
        return montarEndpoint("CTe.svc");
    }

    private String montarEndpoint(String servico) {
        String baseUrl = vedacitApiBaseUrl.endsWith("/")
                ? vedacitApiBaseUrl.substring(0, vedacitApiBaseUrl.length() - 1)
                : vedacitApiBaseUrl;

        return baseUrl + "/" + servico;
    }

    private Ocorrencia converterParaVedacit(
            EslOcorrenciaDTO origem,
            String dataOcorrencia,
            String codigoOcorrenciaDestino
    ) {
        ObjectFactory factory = new ObjectFactory();

        TipoOcorrencia tipoOcorrencia = new TipoOcorrencia();
        tipoOcorrencia.setCodigoIntegracao(factory.createTipoOcorrenciaCodigoIntegracao(codigoOcorrenciaDestino));
        tipoOcorrencia.setDescricao(factory.createTipoOcorrenciaDescricao("Entrega Realizada"));

        Ocorrencia destino = new Ocorrencia();
        destino.setCodigoOcorrencia(origem.occurrence().code());
        destino.setDataOcorrencia(factory.createOcorrenciaDataOcorrencia(dataOcorrencia));
        destino.setNumeroNotaFiscal(Integer.valueOf(origem.invoice().number()));
        destino.setSerieNotaFiscal(factory.createOcorrenciaSerieNotaFiscal(origem.invoice().series()));
        destino.setLatitude(factory.createOcorrenciaLatitude("0"));
        destino.setLongitude(factory.createOcorrenciaLongitude("0"));
        destino.setObservacao(factory.createOcorrenciaObservacao(OBSERVACAO_ENTREGA));
        destino.setTipoOcorrencia(factory.createOcorrenciaTipoOcorrencia(tipoOcorrencia));

        return destino;
    }

    private void enviarOcorrencia(
            EslOcorrenciaDTO ocorrencia,
            String dataOcorrencia,
            String chaveNfe,
            String cteKey
    ) throws Exception {
        String codigoOcorrenciaDestino = String.format("%03d", ocorrencia.occurrence().code());
        Ocorrencia ocorrenciaVedacit = converterParaVedacit(
                ocorrencia,
                dataOcorrencia,
                codigoOcorrenciaDestino
        );

        IOcorrencias porta = criarPortaOcorrencias();

        log.info("📤 [VEDACIT] NF {}: Enviando ocorrência para MultiTMS...", chaveNfe);
        RetornoOfint retorno;
        try {
            retorno = executarSoapComPrazo(
                    () -> porta.adicionarOcorrencia(ocorrenciaVedacit),
                    "ocorrência"
            );
        } catch (Exception e) {
            if (erroDuplicidadeVedacit(e)) {
                logarConciliacaoDuplicidadeVedacit("Ocorrência", chaveNfe, cteKey);
                return;
            }

            throw e;
        }

        if (retorno != null && Boolean.FALSE.equals(retorno.isStatus())) {
            String mensagem = obterMensagem(retorno);
            if (textoIndicaDuplicidadeVedacit(mensagem)) {
                logarConciliacaoDuplicidadeVedacit("Ocorrência", chaveNfe, cteKey);
                return;
            }

            throw new IllegalStateException("Vedacit recusou a ocorrência: " + mensagem);
        }

        log.info("✅ [VEDACIT] NF {}: Ocorrência enviada com sucesso! CTe={}", chaveNfe, cteKey);
    }

    private Canhoto converterParaCanhoto(
            EslOcorrenciaDTO ocorrencia,
            ComprovanteEslDTO comprovante,
            String dataEntrega,
            DateTimeFormatter formatter
    ) throws Exception {
        String chaveNfe = ocorrencia.invoice().key();
        String cteKey = ocorrencia.freight().cteKey();
        Optional<byte[]> canhotoSftp = buscarComprovanteSftp(cteKey, chaveNfe);
        byte[] imagemOriginal;
        if (canhotoSftp.isPresent()) {
            imagemOriginal = canhotoSftp.get();
            log.info("⬇️ [VEDACIT] NF {}: Canhoto obtido via SFTP. CTe={}", chaveNfe, cteKey);
        } else {
            if (canhotoExclusivamenteSftp) {
                throw new CanhotoIndisponivelNaOrigemException(
                        "Canhoto não encontrado no SFTP para o lote exclusivo; fallback ESL desabilitado"
                );
            }
            String urlImagem = obterUrlImagem(obterComprovanteEslFallback(comprovante, cteKey));
            log.info("⬇️ [VEDACIT] NF {}: Baixando imagem do canhoto via ESL... CTe={}", chaveNfe, cteKey);
            imagemOriginal = imageDownloader.baixarImagemDaUrl(urlImagem, cteKey);
        }
        log.info("🖼️ [VEDACIT] NF {}: Imagem baixada com sucesso ({} bytes).", chaveNfe, imagemOriginal.length);

        byte[] imagemComprimida = comprimirImagemParaVedacit(chaveNfe, cteKey, imagemOriginal);
        log.info("🖼️ [VEDACIT] NF {}: Imagem comprimida para {} bytes antes do Base64.", chaveNfe, imagemComprimida.length);

        String imagemBase64Bruta = Base64.getEncoder().encodeToString(imagemComprimida);
        log.info("🛠️ [VEDACIT] NF {}: Imagem preparada para digitalização SOAP. tamanho_base64={}", chaveNfe, imagemBase64Bruta.length());

        com.example.satelite.vedacit.nfe.ObjectFactory factory = new com.example.satelite.vedacit.nfe.ObjectFactory();
        Canhoto canhoto = new Canhoto();

        canhoto.setChaveAcesso(factory.createCanhotoChaveAcesso(chaveNfe));
        canhoto.setChaveAcessoCte(factory.createCanhotoChaveAcessoCte(cteKey));
        canhoto.setDataEntregaNota(factory.createCanhotoDataEntregaNota(dataEntrega));
        canhoto.setDataEnvioCanhoto(factory.createCanhotoDataEnvioCanhoto(LocalDateTime.now().format(formatter)));
        canhoto.setImagemCanhotoBase64(factory.createCanhotoImagemCanhotoBase64(imagemBase64Bruta));
        canhoto.setNomeImagemCanhoto(factory.createCanhotoNomeImagemCanhoto("canhoto_" + chaveNfe + ".jpg"));
        canhoto.setLatitude(factory.createCanhotoLatitude("0"));
        canhoto.setLongitude(factory.createCanhotoLongitude("0"));
        canhoto.setNumeroNotaFiscal(factory.createCanhotoNumeroNotaFiscal(ocorrencia.invoice().number()));
        canhoto.setSerieNotaFiscal(factory.createCanhotoSerieNotaFiscal(ocorrencia.invoice().series()));
        canhoto.setObservacao(factory.createCanhotoObservacao(OBSERVACAO_CANHOTO));

        return canhoto;
    }

    private byte[] comprimirImagemParaVedacit(String chaveNfe, String cteKey, byte[] imagemOriginal) throws IOException {
        try {
            return ImageUtils.comprimirImagemParaVedacit(imagemOriginal);
        } catch (IOException | IllegalArgumentException e) {
            log.warn(
                    "⚠️ [VEDACIT] NF {}: Canhoto com formato/tamanho inválido para compressão. CTe={} mensagem={}",
                    chaveNfe,
                    cteKey,
                    e.getMessage()
            );
            throw new IOException("Canhoto com formato/tamanho invalido para Vedacit: " + e.getMessage(), e);
        }
    }

    private void enviarCanhoto(Canhoto canhoto, String chaveNfe, String cteKey) throws Exception {
        INFe porta = criarPortaNFe();

        log.info("📤 [VEDACIT] NF {}: Enviando digitalização do canhoto...", chaveNfe);
        RetornoOfboolean retorno;
        try {
            retorno = executarSoapComPrazo(
                    () -> porta.enviarDigitalizacaoCanhoto(canhoto),
                    "digitalização do canhoto"
            );
        } catch (Exception e) {
            if (erroDuplicidadeVedacit(e)) {
                logarConciliacaoDuplicidadeVedacit("Canhoto", chaveNfe, cteKey);
                return;
            }

            throw e;
        }

        if (retorno != null && Boolean.FALSE.equals(retorno.isStatus())) {
            String mensagem = obterMensagem(retorno);
            if (textoIndicaDuplicidadeVedacit(mensagem)) {
                logarConciliacaoDuplicidadeVedacit("Canhoto", chaveNfe, cteKey);
                return;
            }

            throw new IllegalStateException("Vedacit recusou o canhoto: " + mensagem);
        }

        log.info("✅ [VEDACIT] NF {}: Canhoto enviado com sucesso! CTe={}", chaveNfe, cteKey);
    }

    private byte[] baixarXmlCte(EslOcorrenciaDTO ocorrencia, String chaveNfe) {
        String chaveCte = obterChaveCteObrigatoria(ocorrencia);
        return baixarXmlCte(chaveCte, chaveNfe);
    }

    private byte[] baixarXmlCte(String chaveCte, String chaveNfe) {
        Optional<byte[]> xmlSftp = buscarXmlCteSftp(chaveCte, chaveNfe);
        if (xmlSftp.isPresent()) {
            log.info("📄 [VEDACIT] NF {}: XML CT-e obtido via SFTP. CTe={}", chaveNfe, chaveCte);
            return xmlSftp.get();
        }

        return baixarXmlCteEsl(chaveCte, chaveNfe);
    }

    private Optional<byte[]> buscarXmlCteSftp(String chaveCte, String chaveNfe) {
        if (vedacitSftpDocumentSource == null) {
            return Optional.empty();
        }
        try {
            return vedacitSftpDocumentSource.buscarXmlCte(chaveCte, chaveNfe)
                    .map(documento -> documento.conteudo())
                    .filter(xml -> xml.length > 0)
                    .filter(xml -> {
                        String texto = new String(xml, StandardCharsets.UTF_8);
                        return texto.contains(chaveCte) && texto.contains(chaveNfe);
                    });
        } catch (RuntimeException e) {
            log.warn("⚠️ [VEDACIT] SFTP indisponível para XML CT-e; usando fallback ESL. CTe={} motivo={}", chaveCte, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<byte[]> buscarComprovanteSftp(String chaveCte, String chaveNfe) {
        if (vedacitSftpDocumentSource == null) {
            return Optional.empty();
        }
        try {
            return vedacitSftpDocumentSource.buscarComprovante(chaveCte, chaveNfe)
                    .map(documento -> documento.conteudo())
                    .filter(conteudo -> conteudo.length > 0);
        } catch (RuntimeException e) {
            log.warn("⚠️ [VEDACIT] SFTP indisponível para canhoto; usando fallback ESL. CTe={} motivo={}",
                    chaveCte, e.getMessage());
            return Optional.empty();
        }
    }

    private ComprovanteEslDTO obterComprovanteEslFallback(ComprovanteEslDTO comprovanteAtual, String chaveCte) {
        if (comprovanteTemImagem(comprovanteAtual)) {
            return comprovanteAtual;
        }

        try {
            ComprovanteEslDTO comprovante = eslRequestPolicyService.executarComTelemetria(
                    EslRequestContext.criar("VEDACIT", "DELIVERY_RECEIPT"),
                    () -> rodogarciaClient.buscarComprovante("Bearer " + obterTokenComprovanteEsl(), chaveCte)
            );
            if (!comprovanteTemImagem(comprovante)) {
                throw new CanhotoIndisponivelNaOrigemException("Canhoto ainda não disponível na ESL");
            }
            return comprovante;
        } catch (EslRequestTransientException e) {
            throw e;
        } catch (CanhotoIndisponivelNaOrigemException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CanhotoIndisponivelNaOrigemException("Canhoto ainda não disponível na ESL", e);
        }
    }

    private String obterTokenComprovanteEsl() {
        if (tokenComprovanteEsl != null && !tokenComprovanteEsl.isBlank()) {
            return tokenComprovanteEsl.trim();
        }
        if (tokenCteXmlEsl != null && !tokenCteXmlEsl.isBlank()) {
            return tokenCteXmlEsl.trim();
        }
        if (tokenVedacitEsl != null && !tokenVedacitEsl.isBlank()) {
            return tokenVedacitEsl.trim();
        }
        throw new IllegalStateException("Token ESL Vedacit ausente para fallback do comprovante");
    }

    private byte[] baixarXmlCteEsl(String chaveCte, String chaveNfe) {
        String token = obterTokenCteXmlEsl();

        log.info("Baixando XML do CT-e na ESL usando a chave: {}", chaveCte);
        CteResponseDTO response = eslRequestPolicyService.executarComTelemetria(
                EslRequestContext.criar("VEDACIT", "CTE_XML"),
                () -> rodogarciaClient.buscarXmlCte("Bearer " + token, chaveCte)
        );
        String xmlString = extrairXmlCte(response);
        byte[] xmlCte = xmlString.getBytes(StandardCharsets.UTF_8);

        log.info("📄 [VEDACIT] NF {}: XML do CT-e baixado com sucesso ({} bytes). CTe={}", chaveNfe, xmlCte.length, chaveCte);
        return xmlCte;
    }

    private String textoObrigatorio(String valor, String mensagem) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(mensagem);
        }
        return valor.trim();
    }

    private String extrairXmlCte(CteResponseDTO response) {
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new XmlCteIndisponivelNaOrigemException("XML do CT-e não encontrado na ESL");
        }

        CteDataDTO primeiroItem = response.data().get(0);
        CteItemDTO cte = primeiroItem == null ? null : primeiroItem.cte();

        if (cte == null || cte.xml() == null || cte.xml().isBlank()) {
            throw new XmlCteIndisponivelNaOrigemException("Resposta da ESL sem XML do CT-e");
        }

        return cte.xml();
    }

    private static final class XmlCteIndisponivelNaOrigemException extends RuntimeException {
        private XmlCteIndisponivelNaOrigemException(String mensagem) {
            super(mensagem);
        }
    }

    private static final class CanhotoIndisponivelNaOrigemException extends RuntimeException {
        private CanhotoIndisponivelNaOrigemException(String mensagem) {
            super(mensagem);
        }

        private CanhotoIndisponivelNaOrigemException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }

    private void enviarXmlCte(byte[] xmlCte, String chaveNfe, String cteKey) throws Exception {
        ICTe porta = criarPortaCte();

        log.info("📤 [VEDACIT] NF {}: Enviando XML do CT-e para MultiTMS... CTe={}", chaveNfe, cteKey);
        RetornoOfstring retorno;
        try {
            retorno = executarSoapComPrazo(
                    () -> porta.enviarArquivoXMLCTe(xmlCte),
                    "XML do CT-e"
            );
        } catch (Exception e) {
            if (erroDuplicidadeVedacit(e)) {
                logarConciliacaoDuplicidadeVedacit("XML do CT-e", chaveNfe, cteKey);
                return;
            }

            throw e;
        }

        if (retorno != null && Boolean.FALSE.equals(retorno.isStatus())) {
            String mensagem = obterMensagem(retorno);
            if (textoIndicaDuplicidadeVedacit(mensagem)) {
                logarConciliacaoDuplicidadeVedacit("XML do CT-e", chaveNfe, cteKey);
                return;
            }

            throw new IllegalStateException("Vedacit recusou o XML do CT-e: " + mensagem);
        }

        log.info("✅ [VEDACIT] NF {}: XML do CT-e enviado com sucesso! CTe={}", chaveNfe, cteKey);
    }

    protected IOcorrencias criarPortaOcorrencias() throws Exception {
        IOcorrencias porta = new Ocorrencias(obterWsdlLocal(WSDL_OCORRENCIAS))
                .getBasicHttpBindingIOcorrencias();
        configurarPortaSoap(porta, montarEndpointOcorrencias());
        return porta;
    }

    protected INFe criarPortaNFe() throws Exception {
        INFe porta = new NFe(obterWsdlLocal(WSDL_NFE))
                .getBasicHttpBindingINFe();
        configurarPortaSoap(porta, montarEndpointNFe());
        return porta;
    }

    protected ICTe criarPortaCte() throws Exception {
        ICTe porta = new CTe(obterWsdlLocal(WSDL_CTE))
                .getBasicHttpBindingICTe();
        configurarPortaSoap(porta, montarEndpointCte());
        return porta;
    }

    private URL obterWsdlLocal(String caminhoClasspath) {
        URL wsdlUrl = getClass().getResource(caminhoClasspath);
        if (wsdlUrl == null) {
            throw new IllegalStateException("WSDL local nao encontrado no classpath: " + caminhoClasspath);
        }

        return wsdlUrl;
    }

    private boolean erroDuplicidadeVedacit(Throwable erro) {
        return textoIndicaDuplicidadeVedacit(extrairTextoErro(erro));
    }

    private boolean textoIndicaDuplicidadeVedacit(String texto) {
        String textoNormalizado = normalizarTextoErro(texto);
        return textoNormalizado.contains("ja existe")
                || textoNormalizado.contains("ja cadastr")
                || textoNormalizado.contains("duplicad")
                || textoNormalizado.contains("duplicidade");
    }

    private String extrairTextoErro(Throwable erro) {
        StringBuilder texto = new StringBuilder();
        Throwable atual = erro;

        while (atual != null) {
            if (atual.getMessage() != null) {
                texto.append(atual.getMessage()).append(' ');
            }

            if (atual instanceof SOAPFaultException soapFaultException
                    && soapFaultException.getFault() != null
                    && soapFaultException.getFault().getFaultString() != null) {
                texto.append(soapFaultException.getFault().getFaultString()).append(' ');
            }

            Throwable causa = atual.getCause();
            atual = causa == atual ? null : causa;
        }

        return texto.toString();
    }

    private String normalizarTextoErro(String texto) {
        if (texto == null || texto.isBlank()) {
            return "";
        }

        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private void logarConciliacaoDuplicidadeVedacit(String etapa, String chaveNfe, String cteKey) {
        log.info("Aviso: Destino informou duplicidade. Conciliando... [VEDACIT] {} NF {} CTe={}", etapa, chaveNfe, cteKey);
    }

    @SuppressWarnings("rawtypes")
    private void configurarPortaSoap(Object porta, String endpoint) {
        BindingProvider bindingProvider = (BindingProvider) porta;
        bindingProvider.getRequestContext().put(
                BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
                endpoint
        );
        configurarTimeoutsSoap(bindingProvider);

        Binding binding = bindingProvider.getBinding();
        List<Handler> handlerChain = new ArrayList<>(binding.getHandlerChain());
        handlerChain.add(new VedacitTokenHeaderHandler(vedacitToken));
        binding.setHandlerChain(handlerChain);
    }

    private void configurarTimeoutsSoap(BindingProvider bindingProvider) {
        bindingProvider.getRequestContext().put("com.sun.xml.ws.connect.timeout", soapConnectTimeoutMs);
        bindingProvider.getRequestContext().put("com.sun.xml.ws.request.timeout", soapReadTimeoutMs);
        bindingProvider.getRequestContext().put("javax.xml.ws.client.connectionTimeout", String.valueOf(soapConnectTimeoutMs));
        bindingProvider.getRequestContext().put("javax.xml.ws.client.receiveTimeout", String.valueOf(soapReadTimeoutMs));
        bindingProvider.getRequestContext().put("jakarta.xml.ws.client.connectionTimeout", String.valueOf(soapConnectTimeoutMs));
        bindingProvider.getRequestContext().put("jakarta.xml.ws.client.receiveTimeout", String.valueOf(soapReadTimeoutMs));
    }

    private <T> T executarSoapComPrazo(Callable<T> chamada, String etapa) throws Exception {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "vedacit-soap-" + etapa.replaceAll("[^a-zA-Z0-9]", "-"));
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory);
        Future<T> future = executor.submit(chamada);
        try {
            return future.get(soapInvocationTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("Timeout total da Vedacit na etapa " + etapa + " após " + soapInvocationTimeoutMs + " ms", e);
        } catch (ExecutionException e) {
            Throwable causa = e.getCause();
            if (causa instanceof Exception exception) {
                throw exception;
            }
            if (causa instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Falha desconhecida no SOAP Vedacit: " + etapa, causa);
        } finally {
            executor.shutdownNow();
        }
    }

    private String obterUrlImagem(ComprovanteEslDTO comprovante) {
        if (comprovante == null || comprovante.data() == null || comprovante.data().isEmpty()) {
            throw new IllegalStateException("Comprovante de entrega sem imagem");
        }

        ComprovanteEslItemDTO primeiroComprovante = comprovante.data().get(0);
        if (primeiroComprovante == null || primeiroComprovante.imageUrl() == null || primeiroComprovante.imageUrl().isBlank()) {
            throw new IllegalStateException("URL da imagem do comprovante ausente");
        }

        return primeiroComprovante.imageUrl();
    }

    private String obterChaveNfeLog(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia == null || ocorrencia.invoice() == null || ocorrencia.invoice().key() == null) {
            return "NAO_INFORMADO";
        }

        return ocorrencia.invoice().key();
    }

    private String obterChaveCteLog(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia == null || ocorrencia.freight() == null || ocorrencia.freight().cteKey() == null) {
            return "NAO_INFORMADO";
        }

        return ocorrencia.freight().cteKey();
    }

    private String obterChaveCteObrigatoria(EslOcorrenciaDTO ocorrencia) {
        if (chaveCteAusente(ocorrencia)) {
            throw new IllegalStateException("Chave CTe ausente para consulta do XML");
        }

        return ocorrencia.freight().cteKey();
    }

    private boolean chaveCteAusente(EslOcorrenciaDTO ocorrencia) {
        return ocorrencia == null
                || ocorrencia.freight() == null
                || ocorrencia.freight().cteKey() == null
                || ocorrencia.freight().cteKey().isBlank();
    }

    private String obterTokenCteXmlEsl() {
        if (tokenCteXmlEsl == null || tokenCteXmlEsl.isBlank()) {
            throw new IllegalStateException("RODOGARCIA_MASTER_API_REST não configurado para consulta do XML do CT-e");
        }

        return tokenCteXmlEsl;
    }

    private Set<String> obterWhitelistNfe() {
        if (nfeWhitelist == null || nfeWhitelist.isBlank()) {
            return Set.of();
        }

        return Arrays.stream(nfeWhitelist.split(","))
                .map(chave -> java.util.Objects.requireNonNull(chave, "Chave de whitelist ausente").trim())
                .filter(chave -> !chave.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String obterMensagem(RetornoOfint retorno) {
        if (retorno.getMensagem() == null) {
            return "sem mensagem de retorno";
        }

        return retorno.getMensagem().getValue();
    }

    private String obterMensagem(RetornoOfboolean retorno) {
        if (retorno.getMensagem() == null) {
            return "sem mensagem de retorno";
        }

        return retorno.getMensagem().getValue();
    }

    private String obterMensagem(RetornoOfstring retorno) {
        if (retorno.getMensagem() == null) {
            return "sem mensagem de retorno";
        }

        return retorno.getMensagem().getValue();
    }
}
