package com.example.satelite.vedacit.cte.sgt;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "RetornoOfstring",
        namespace = ObjectFactory.NAMESPACE,
        propOrder = {
                "mensagem",
                "retorno",
                "status"
        }
)
public class RetornoOfstring {

    @XmlElementRef(name = "Mensagem", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> mensagem;

    @XmlElement(name = "Retorno")
    private String retorno;

    @XmlElement(name = "Status")
    private Boolean status;

    public JAXBElement<String> getMensagem() {
        return mensagem;
    }

    public void setMensagem(JAXBElement<String> mensagem) {
        this.mensagem = mensagem;
    }

    public String getRetorno() {
        return retorno;
    }

    public void setRetorno(String retorno) {
        this.retorno = retorno;
    }

    public Boolean isStatus() {
        return status;
    }

    public void setStatus(Boolean status) {
        this.status = status;
    }
}
