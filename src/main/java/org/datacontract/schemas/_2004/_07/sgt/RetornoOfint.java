package org.datacontract.schemas._2004._07.sgt;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "RetornoOfint",
        namespace = ObjectFactory.NAMESPACE,
        propOrder = {
                "mensagem",
                "retorno",
                "status"
        }
)
public class RetornoOfint {

    @XmlElementRef(name = "Mensagem", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> mensagem;

    @XmlElement(name = "Retorno")
    private Integer retorno;

    @XmlElement(name = "Status")
    private Boolean status;

    public JAXBElement<String> getMensagem() {
        return mensagem;
    }

    public void setMensagem(JAXBElement<String> mensagem) {
        this.mensagem = mensagem;
    }

    public Integer getRetorno() {
        return retorno;
    }

    public void setRetorno(Integer retorno) {
        this.retorno = retorno;
    }

    public Boolean isStatus() {
        return status;
    }

    public void setStatus(Boolean status) {
        this.status = status;
    }
}
