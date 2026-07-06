package org.datacontract.schemas._2004._07.sgt;

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

    public RetornoOfint createRetornoOfint() {
        return new RetornoOfint();
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "Mensagem", scope = RetornoOfint.class)
    public JAXBElement<String> createRetornoOfintMensagem(String value) {
        return new JAXBElement<>(RETORNO_MENSAGEM, String.class, RetornoOfint.class, value);
    }
}
