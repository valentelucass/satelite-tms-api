package com.example.satelite.utils;

import java.io.ByteArrayInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.helpers.DefaultHandler;

/** Confere a correlação documental; não substitui a validação fiscal/XSD do destino. */
public final class CteXmlValidator {
    private static final String NAMESPACE_CTE = "http://www.portalfiscal.inf.br/cte";

    private CteXmlValidator() { }

    public static boolean corresponde(byte[] xml, String chaveCte, String chaveNfe) {
        if (xml == null || chaveCte == null || chaveNfe == null
                || !chaveCte.matches("\\d{44}") || !chaveNfe.matches("\\d{44}")) return false;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            var document = builder.parse(new ByteArrayInputStream(xml));
            var identificacoes = document.getElementsByTagNameNS(NAMESPACE_CTE, "infCte");
            if (identificacoes.getLength() != 1) return false;
            Element infCte = (Element) identificacoes.item(0);
            if (!("CTe" + chaveCte).equals(infCte.getAttribute("Id"))) return false;
            var notas = infCte.getElementsByTagNameNS(NAMESPACE_CTE, "infNFe");
            for (int i = 0; i < notas.getLength(); i++) {
                var chaves = ((Element) notas.item(i)).getElementsByTagNameNS(NAMESPACE_CTE, "chave");
                for (int j = 0; j < chaves.getLength(); j++) {
                    if (chaveNfe.equals(chaves.item(j).getTextContent().trim())) return true;
                }
            }
        } catch (Exception e) {
            // XML inválido é inelegível; nunca registrar conteúdo ou resolver entidades externas.
            return false;
        }
        return false;
    }
}
