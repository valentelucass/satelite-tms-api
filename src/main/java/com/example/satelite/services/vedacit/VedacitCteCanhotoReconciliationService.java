package com.example.satelite.services.vedacit;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.CteDataDTO;
import com.example.satelite.dto.rodogarcia.CteResponseDTO;
import com.example.satelite.services.etl.EslRequestContext;
import com.example.satelite.services.etl.EslRequestPolicyService;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocument;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocumentSource;

/** Resolve de forma auditável o CT-e do canhoto, sem enviar SOAP nem alterar auditoria. */
@Service
public class VedacitCteCanhotoReconciliationService {

    private final RodogarciaClient rodogarciaClient;
    private final EslRequestPolicyService eslRequestPolicyService;
    private final VedacitSftpDocumentSource sftp;
    private final String tokenCteXml;

    public VedacitCteCanhotoReconciliationService(
            RodogarciaClient rodogarciaClient,
            EslRequestPolicyService eslRequestPolicyService,
            VedacitSftpDocumentSource sftp,
            @Value("${RODOGARCIA_MASTER_API_REST:}") String tokenCteXml
    ) {
        this.rodogarciaClient = rodogarciaClient;
        this.eslRequestPolicyService = eslRequestPolicyService;
        this.sftp = sftp;
        this.tokenCteXml = tokenCteXml;
    }

    public Decisao reconciliar(String chaveNfe, String chaveCteOriginal) {
        validarChave(chaveNfe, "NF-e");
        validarChave(chaveCteOriginal, "CT-e original");
        List<VedacitSftpDocument> documentos = sftp.buscarComprovantesPorNfe(chaveNfe);
        Optional<VedacitSftpDocument> exato = documentos.stream()
                .filter(documento -> chaveCteOriginal.equals(documento.chaveCte()))
                .findFirst();
        if (exato.isPresent()) {
            return Decisao.encontrada(chaveCteOriginal, "EXATO", "Arquivo SFTP corresponde ao CT-e original", exato.get());
        }
        if (documentos.isEmpty()) {
            return Decisao.pendente("Não há comprovante SFTP válido para a NF-e");
        }

        CteClassificacao classificacao = classificarPorXmlOficial(chaveCteOriginal);
        if ("1".equals(classificacao.tipoCte()) && classificacao.cteReferenciado() != null) {
            return documentos.stream()
                    .filter(documento -> classificacao.cteReferenciado().equals(documento.chaveCte()))
                    .findFirst()
                    .map(documento -> Decisao.encontrada(
                            documento.chaveCte(),
                            "COMPLEMENTAR_PARA_TRANSPORTE",
                            "XML complementar referencia explicitamente o CT-e efetivo",
                            documento
                    ))
                    .orElseGet(() -> Decisao.pendente(
                            "CT-e complementar referencia " + classificacao.cteReferenciado() + ", mas não há comprovante SFTP correspondente"
                    ));
        }

        if (documentos.size() == 1) {
            VedacitSftpDocument documento = documentos.get(0);
            return Decisao.encontrada(
                    documento.chaveCte(),
                    "FTP_UNICO_PARA_NFE",
                    "Único comprovante SFTP válido da NF-e; CT-e efetivo confirmado pela regra de negócio",
                    documento
            );
        }
        return Decisao.pendente("Não há comprovante exato e existem " + documentos.size() + " candidatos SFTP para a NF-e");
    }

    private CteClassificacao classificarPorXmlOficial(String chaveCte) {
        if (tokenCteXml == null || tokenCteXml.isBlank()) {
            throw new IllegalStateException("RODOGARCIA_MASTER_API_REST ausente para reconciliação de CT-e");
        }
        CteResponseDTO resposta = eslRequestPolicyService.executarComTelemetria(
                EslRequestContext.criar("VEDACIT", "CTE_RECONCILIATION"),
                () -> rodogarciaClient.buscarXmlCte("Bearer " + tokenCteXml.trim(), chaveCte)
        );
        String xml = extrairXmlOficial(resposta);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return new CteClassificacao(texto(document, "tpCTe"), texto(document, "chCTe"));
        } catch (Exception e) {
            throw new IllegalStateException("XML oficial do CT-e inválido para reconciliação", e);
        }
    }

    private String extrairXmlOficial(CteResponseDTO resposta) {
        if (resposta == null || resposta.data() == null) {
            throw new IllegalStateException("XML oficial do CT-e não disponível para reconciliação");
        }
        for (CteDataDTO dado : resposta.data()) {
            if (dado == null || dado.cte() == null) continue;
            String xml = dado.cte().xml();
            if (xml != null && !xml.isBlank()) return xml;
        }
        throw new IllegalStateException("XML oficial do CT-e não disponível para reconciliação");
    }

    private String texto(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            nodes = document.getElementsByTagName(localName);
        }
        if (nodes.getLength() == 0) return null;
        String valor = nodes.item(0).getTextContent();
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    private void validarChave(String chave, String rotulo) {
        if (chave == null || !chave.matches("\\d{44}")) throw new IllegalArgumentException(rotulo + " inválido para reconciliação");
    }

    private record CteClassificacao(String tipoCte, String cteReferenciado) { }

    public record Decisao(
            boolean encontrada, String chaveCteEfetiva, String tipo, String motivo, VedacitSftpDocument documento
    ) {
        static Decisao encontrada(String chaveCte, String tipo, String motivo, VedacitSftpDocument documento) {
            return new Decisao(true, chaveCte, tipo, motivo, documento);
        }
        static Decisao pendente(String motivo) {
            return new Decisao(false, null, "PENDENTE_RECONCILIACAO", motivo, null);
        }
    }
}
