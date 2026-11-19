package com.example.satelite.utils;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
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
        return identificar(xml).map(id -> id.chaveCte().equals(chaveCte) && id.chavesNfe().contains(chaveNfe)).orElse(false);
    }

    public static Optional<Identificacao> identificar(byte[] xml) {
        if (xml == null) return Optional.empty();
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
            if (identificacoes.getLength() != 1) return Optional.empty();
            Element infCte = (Element) identificacoes.item(0);
            String id = infCte.getAttribute("Id");
            if (!id.matches("CTe\\d{44}")) return Optional.empty();
            Set<String> nfes = new HashSet<>();
            var notas = infCte.getElementsByTagNameNS(NAMESPACE_CTE, "infNFe");
            for (int i = 0; i < notas.getLength(); i++) {
                var chaves = ((Element) notas.item(i)).getElementsByTagNameNS(NAMESPACE_CTE, "chave");
                for (int j = 0; j < chaves.getLength(); j++) {
                    String chave = chaves.item(j).getTextContent().trim();
                    if (chave.matches("\\d{44}")) nfes.add(chave);
                }
            }
            return Optional.of(new Identificacao(id.substring(3), Set.copyOf(nfes)));
        } catch (Exception e) {
            // XML inválido é inelegível; nunca registrar conteúdo ou resolver entidades externas.
            return Optional.empty();
        }
    }

    public record Identificacao(String chaveCte, Set<String> chavesNfe) { }
}
