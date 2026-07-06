package com.example.satelite.vedacit.cte.sgt;

import javax.xml.namespace.QName;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlElementDecl;
import jakarta.xml.bind.annotation.XmlRegistry;

@XmlRegistry
public class ObjectFactory {

    public static final String NAMESPACE = "http://schemas.datacontract.org/2004/07/SGT";

    private static final QName RETORNO_MENSAGEM = new QName(NAMESPACE, "Mensagem");

    public ObjectFactory() {
    }

    public RetornoOfstring createRetornoOfstring() {
        return new RetornoOfstring();
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "Mensagem", scope = RetornoOfstring.class)
    public JAXBElement<String> createRetornoOfstringMensagem(String value) {
        return new JAXBElement<>(RETORNO_MENSAGEM, String.class, RetornoOfstring.class, value);
    }
}
