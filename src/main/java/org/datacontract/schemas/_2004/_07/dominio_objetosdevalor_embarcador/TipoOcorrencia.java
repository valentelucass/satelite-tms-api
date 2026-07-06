package org.datacontract.schemas._2004._07.dominio_objetosdevalor_embarcador;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "TipoOcorrencia",
        namespace = ObjectFactory.NAMESPACE,
        propOrder = {
                "codigoIntegracao",
                "descricao"
        }
)
public class TipoOcorrencia {

    @XmlElementRef(name = "CodigoIntegracao", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> codigoIntegracao;

    @XmlElementRef(name = "Descricao", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> descricao;

    public JAXBElement<String> getCodigoIntegracao() {
        return codigoIntegracao;
    }

    public void setCodigoIntegracao(JAXBElement<String> codigoIntegracao) {
        this.codigoIntegracao = codigoIntegracao;
    }

    public JAXBElement<String> getDescricao() {
        return descricao;
    }

    public void setDescricao(JAXBElement<String> descricao) {
        this.descricao = descricao;
    }
}
