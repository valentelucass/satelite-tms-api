package com.example.satelite.services.vedacit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import com.example.satelite.services.etl.EslRequestPolicyService.EslRequestTransientException;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class VedacitIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(VedacitIntegrationService.class);
    private static final String OBSERVACAO_ENTREGA = "Entrega Realizada";
    private static final String OBSERVACAO_CANHOTO = "Canhoto integrado pelo Satelite TMS";

    @Value("${VEDACIT_API_TOKEN}")
    private String vedacitToken;

    @Value("${VEDACIT_API_BASE_URL}")
    private String vedacitApiBaseUrl;

    @Value("${RODOGARCIA_MASTER_API_REST:}")
    private String tokenCteXmlEsl;

    @Value("${VEDACIT_SEND_OCCURRENCE_ENABLED:true}")
    private boolean envioOcorrenciaHabilitado;

    @Value("${VEDACIT_SEND_CANHOTO_ENABLED:true}")
    private boolean envioCanhotoHabilitado;

    @Value("${VEDACIT_SEND_CTE_XML_ENABLED:false}")
    private boolean envioXmlCteHabilitado;

    @Value("${VEDACIT_NFE_WHITELIST:}")
    private String nfeWhitelist;

    @Value("${VEDACIT_NFE_WHITELIST_ENABLED:true}")
    private boolean whitelistEnabled;

    @Value("${VEDACIT_SOAP_CONNECT_TIMEOUT_MS:30000}")
    private int soapConnectTimeoutMs;

    @Value("${VEDACIT_SOAP_READ_TIMEOUT_MS:60000}")
    private int soapReadTimeoutMs;

    private final ImageDownloader imageDownloader;
    private final RodogarciaClient rodogarciaClient;
    private final EslRequestPolicyService eslRequestPolicyService;

    public VedacitIntegrationService(
            ImageDownloader imageDownloader,
            RodogarciaClient rodogarciaClient,
            EslRequestPolicyService eslRequestPolicyService
    ) {
        this.imageDownloader = imageDownloader;
        this.rodogarciaClient = rodogarciaClient;
        this.eslRequestPolicyService = eslRequestPolicyService;
    }

    public ResultadoIntegracao processarOcorrencia(EslOcorrenciaDTO ocorrencia, ComprovanteEslDTO comprovante) {
        return processarOcorrencia(ocorrencia, comprovante, false, false);
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

        if (!comprovanteTemImagem(comprovante)) {
            log.warn("⏳ [VEDACIT] NF {}: Canhoto ainda não disponível na ESL. CTe={}", chaveNfe, cteKey);
            return ResultadoIntegracao.parcialCanhotoPendente(statusDados, "Canhoto ainda não disponível na ESL");
        }

        try {
            Canhoto canhoto = converterParaCanhoto(ocorrencia, comprovante, dataOcorrencia, formatter);
            enviarCanhoto(canhoto, chaveNfe, cteKey);
            statusCanhoto = ResultadoIntegracao.STATUS_SUCESSO;
            return ResultadoIntegracao.vedacitConcluido(statusDados, statusCanhoto);
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
            retorno = porta.adicionarOcorrencia(ocorrenciaVedacit);
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
        String urlImagem = obterUrlImagem(comprovante);
        log.info("⬇️ [VEDACIT] NF {}: Baixando imagem do canhoto... CTe={}", chaveNfe, cteKey);
        byte[] imagemOriginal = imageDownloader.baixarImagemDaUrl(urlImagem, cteKey);
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
            retorno = porta.enviarDigitalizacaoCanhoto(canhoto);
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
        String token = obterTokenCteXmlEsl();

        log.info("Baixando XML do CT-e na ESL usando a chave: {}", chaveCte);
        CteResponseDTO response = eslRequestPolicyService.executar(
                "buscarXmlCte cte_key=" + chaveCte,
                () -> rodogarciaClient.buscarXmlCte("Bearer " + token, chaveCte)
        );
        String xmlString = extrairXmlCte(response);
        byte[] xmlCte = xmlString.getBytes(StandardCharsets.UTF_8);

        log.info("📄 [VEDACIT] NF {}: XML do CT-e baixado com sucesso ({} bytes). CTe={}", chaveNfe, xmlCte.length, chaveCte);
        return xmlCte;
    }

    private String extrairXmlCte(CteResponseDTO response) {
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new IllegalStateException("XML do CT-e não encontrado na ESL");
        }

        CteDataDTO primeiroItem = response.data().get(0);
        CteItemDTO cte = primeiroItem == null ? null : primeiroItem.cte();

        if (cte == null || cte.xml() == null || cte.xml().isBlank()) {
            throw new IllegalStateException("Resposta da ESL sem XML do CT-e");
        }

        return cte.xml();
    }

    private void enviarXmlCte(byte[] xmlCte, String chaveNfe, String cteKey) throws Exception {
        ICTe porta = criarPortaCte();

        log.info("📤 [VEDACIT] NF {}: Enviando XML do CT-e para MultiTMS... CTe={}", chaveNfe, cteKey);
        logarTokenAutenticacaoVedacit();
        RetornoOfstring retorno;
        try {
            retorno = porta.enviarArquivoXMLCTe(xmlCte);
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
        Ocorrencias servico = new Ocorrencias(URI.create(montarEndpointOcorrencias() + "?wsdl").toURL());
        IOcorrencias porta = servico.getBasicHttpBindingIOcorrencias();
        configurarPortaSoap(porta, montarEndpointOcorrencias());
        return porta;
    }

    protected INFe criarPortaNFe() throws Exception {
        NFe servico = new NFe(URI.create(montarEndpointNFe() + "?wsdl").toURL());
        INFe porta = servico.getBasicHttpBindingINFe();
        configurarPortaSoap(porta, montarEndpointNFe());
        return porta;
    }

    protected ICTe criarPortaCte() throws Exception {
        CTe servico = new CTe(URI.create(montarEndpointCte() + "?wsdl").toURL());
        ICTe porta = servico.getBasicHttpBindingICTe();
        configurarPortaSoap(porta, montarEndpointCte());
        return porta;
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

    private void logarTokenAutenticacaoVedacit() {
        int tamanhoToken = vedacitToken == null ? 0 : vedacitToken.length();
        String prefixoToken = obterPrefixoTokenMascarado(tamanhoToken);

        log.info("Token de autenticação utilizado: {}... | Length: {}", prefixoToken, tamanhoToken);
    }

    private String obterPrefixoTokenMascarado(int tamanhoToken) {
        if (vedacitToken == null) {
            return "null";
        }

        if (tamanhoToken < 4) {
            return "<menor-que-4>";
        }

        return vedacitToken.substring(0, 4);
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
        if (ocorrencia == null
                || ocorrencia.freight() == null
                || ocorrencia.freight().cteKey() == null
                || ocorrencia.freight().cteKey().isBlank()) {
            throw new IllegalStateException("Chave CTe ausente para consulta do XML");
        }

        return ocorrencia.freight().cteKey();
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
                .map(String::trim)
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
