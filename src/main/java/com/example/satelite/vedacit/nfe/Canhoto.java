package com.example.satelite.vedacit.nfe;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "Canhoto",
        namespace = ObjectFactory.NAMESPACE,
        propOrder = {
                "chaveAcesso",
                "chaveAcessoCte",
                "dataEntregaNota",
                "dataEnvioCanhoto",
                "imagemCanhotoBase64",
                "nomeImagemCanhoto",
                "latitude",
                "longitude",
                "numeroNotaFiscal",
                "serieNotaFiscal",
                "observacao"
        }
)
public class Canhoto {

    @XmlElementRef(name = "ChaveAcesso", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> chaveAcesso;

    @XmlElementRef(name = "ChaveAcessoCte", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> chaveAcessoCte;

    @XmlElementRef(name = "DataEntregaNota", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> dataEntregaNota;

    @XmlElementRef(name = "DataEnvioCanhoto", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> dataEnvioCanhoto;

    @XmlElementRef(name = "ImagemCanhotoBase64", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> imagemCanhotoBase64;

    @XmlElementRef(name = "NomeImagemCanhoto", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> nomeImagemCanhoto;

    @XmlElementRef(name = "Latitude", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> latitude;

    @XmlElementRef(name = "Longitude", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> longitude;

    @XmlElementRef(name = "NumeroNotaFiscal", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> numeroNotaFiscal;

    @XmlElementRef(name = "SerieNotaFiscal", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> serieNotaFiscal;

    @XmlElementRef(name = "Observacao", namespace = ObjectFactory.NAMESPACE, type = JAXBElement.class, required = false)
    private JAXBElement<String> observacao;

    public JAXBElement<String> getChaveAcesso() {
        return chaveAcesso;
    }

    public void setChaveAcesso(JAXBElement<String> chaveAcesso) {
        this.chaveAcesso = chaveAcesso;
    }

    public JAXBElement<String> getChaveAcessoCte() {
        return chaveAcessoCte;
    }

    public void setChaveAcessoCte(JAXBElement<String> chaveAcessoCte) {
        this.chaveAcessoCte = chaveAcessoCte;
    }

    public JAXBElement<String> getDataEntregaNota() {
        return dataEntregaNota;
    }

    public void setDataEntregaNota(JAXBElement<String> dataEntregaNota) {
        this.dataEntregaNota = dataEntregaNota;
    }

    public JAXBElement<String> getDataEnvioCanhoto() {
        return dataEnvioCanhoto;
    }

    public void setDataEnvioCanhoto(JAXBElement<String> dataEnvioCanhoto) {
        this.dataEnvioCanhoto = dataEnvioCanhoto;
    }

    public JAXBElement<String> getImagemCanhotoBase64() {
        return imagemCanhotoBase64;
    }

    public void setImagemCanhotoBase64(JAXBElement<String> imagemCanhotoBase64) {
        this.imagemCanhotoBase64 = imagemCanhotoBase64;
    }

    public JAXBElement<String> getNomeImagemCanhoto() {
        return nomeImagemCanhoto;
    }

    public void setNomeImagemCanhoto(JAXBElement<String> nomeImagemCanhoto) {
        this.nomeImagemCanhoto = nomeImagemCanhoto;
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

    public JAXBElement<String> getNumeroNotaFiscal() {
        return numeroNotaFiscal;
    }

    public void setNumeroNotaFiscal(JAXBElement<String> numeroNotaFiscal) {
        this.numeroNotaFiscal = numeroNotaFiscal;
    }

    public JAXBElement<String> getSerieNotaFiscal() {
        return serieNotaFiscal;
    }

    public void setSerieNotaFiscal(JAXBElement<String> serieNotaFiscal) {
        this.serieNotaFiscal = serieNotaFiscal;
    }

    public JAXBElement<String> getObservacao() {
        return observacao;
    }

    public void setObservacao(JAXBElement<String> observacao) {
        this.observacao = observacao;
    }
}
