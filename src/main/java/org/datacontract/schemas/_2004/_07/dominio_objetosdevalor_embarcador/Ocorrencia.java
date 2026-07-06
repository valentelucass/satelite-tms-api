package org.datacontract.schemas._2004._07.dominio_objetosdevalor_embarcador;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "Ocorrencia",
        namespace = ObjectFactory.NAMESPACE,
        propOrder = {
                "codigoOcorrencia",
                "dataOcorrencia",
                "numeroNotaFiscal",
                "serieNotaFiscal",
                "latitude",
                "longitude",
                "observacao",
                "tipoOcorrencia"
        }
)
public class Ocorrencia {

    @XmlElement(name = "CodigoOcorrencia")
    private Integer codigoOcorrencia;

    @XmlElementRef(name = "DataOcorrencia", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> dataOcorrencia;

    @XmlElement(name = "NumeroNotaFiscal")
    private Integer numeroNotaFiscal;

    @XmlElementRef(name = "SerieNotaFiscal", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> serieNotaFiscal;

    @XmlElementRef(name = "Latitude", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> latitude;

    @XmlElementRef(name = "Longitude", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> longitude;

    @XmlElementRef(name = "Observacao", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> observacao;

    @XmlElementRef(name = "TipoOcorrencia", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<TipoOcorrencia> tipoOcorrencia;

    public Integer getCodigoOcorrencia() {
        return codigoOcorrencia;
    }

    public void setCodigoOcorrencia(Integer codigoOcorrencia) {
        this.codigoOcorrencia = codigoOcorrencia;
    }

    public JAXBElement<String> getDataOcorrencia() {
        return dataOcorrencia;
    }

    public void setDataOcorrencia(JAXBElement<String> dataOcorrencia) {
        this.dataOcorrencia = dataOcorrencia;
    }

    public Integer getNumeroNotaFiscal() {
        return numeroNotaFiscal;
    }

    public void setNumeroNotaFiscal(Integer numeroNotaFiscal) {
        this.numeroNotaFiscal = numeroNotaFiscal;
    }

    public JAXBElement<String> getSerieNotaFiscal() {
        return serieNotaFiscal;
    }

    public void setSerieNotaFiscal(JAXBElement<String> serieNotaFiscal) {
        this.serieNotaFiscal = serieNotaFiscal;
    }

    public JAXBElement<String> getLatitude() {
        return latitude;
    }

    public void setLatitude(JAXBElement<String> latitude) {
        this.latitude = latitude;
    }

    public JAXBElement<String> getLongitude() {
        return longitude;
    }

    public void setLongitude(JAXBElement<String> longitude) {
        this.longitude = longitude;
    }

    public JAXBElement<String> getObservacao() {
        return observacao;
    }

    public void setObservacao(JAXBElement<String> observacao) {
        this.observacao = observacao;
    }

    public JAXBElement<TipoOcorrencia> getTipoOcorrencia() {
        return tipoOcorrencia;
    }

    public void setTipoOcorrencia(JAXBElement<TipoOcorrencia> tipoOcorrencia) {
        this.tipoOcorrencia = tipoOcorrencia;
    }
}
