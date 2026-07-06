package org.datacontract.schemas._2004._07.dominio_objetosdevalor_embarcador;

import javax.xml.namespace.QName;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlElementDecl;
import jakarta.xml.bind.annotation.XmlRegistry;

@XmlRegistry
public class ObjectFactory {

    public static final String NAMESPACE = "http://schemas.datacontract.org/2004/07/Dominio.ObjetosDeValor.Embarcador";

    private static final QName TIPO_OCORRENCIA_CODIGO_INTEGRACAO = new QName(NAMESPACE, "CodigoIntegracao");
    private static final QName TIPO_OCORRENCIA_DESCRICAO = new QName(NAMESPACE, "Descricao");
    private static final QName OCORRENCIA_DATA_OCORRENCIA = new QName(NAMESPACE, "DataOcorrencia");
    private static final QName OCORRENCIA_SERIE_NOTA_FISCAL = new QName(NAMESPACE, "SerieNotaFiscal");
    private static final QName OCORRENCIA_LATITUDE = new QName(NAMESPACE, "Latitude");
    private static final QName OCORRENCIA_LONGITUDE = new QName(NAMESPACE, "Longitude");
    private static final QName OCORRENCIA_OBSERVACAO = new QName(NAMESPACE, "Observacao");
    private static final QName OCORRENCIA_TIPO_OCORRENCIA = new QName(NAMESPACE, "TipoOcorrencia");

    public ObjectFactory() {
    }

    public Ocorrencia createOcorrencia() {
        return new Ocorrencia();
    }

    public TipoOcorrencia createTipoOcorrencia() {
        return new TipoOcorrencia();
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "CodigoIntegracao", scope = TipoOcorrencia.class)
    public JAXBElement<String> createTipoOcorrenciaCodigoIntegracao(String value) {
        return new JAXBElement<>(TIPO_OCORRENCIA_CODIGO_INTEGRACAO, String.class, TipoOcorrencia.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "Descricao", scope = TipoOcorrencia.class)
    public JAXBElement<String> createTipoOcorrenciaDescricao(String value) {
        return new JAXBElement<>(TIPO_OCORRENCIA_DESCRICAO, String.class, TipoOcorrencia.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "DataOcorrencia", scope = Ocorrencia.class)
    public JAXBElement<String> createOcorrenciaDataOcorrencia(String value) {
        return new JAXBElement<>(OCORRENCIA_DATA_OCORRENCIA, String.class, Ocorrencia.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "SerieNotaFiscal", scope = Ocorrencia.class)
    public JAXBElement<String> createOcorrenciaSerieNotaFiscal(String value) {
        return new JAXBElement<>(OCORRENCIA_SERIE_NOTA_FISCAL, String.class, Ocorrencia.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "Latitude", scope = Ocorrencia.class)
    public JAXBElement<String> createOcorrenciaLatitude(String value) {
        return new JAXBElement<>(OCORRENCIA_LATITUDE, String.class, Ocorrencia.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "Longitude", scope = Ocorrencia.class)
    public JAXBElement<String> createOcorrenciaLongitude(String value) {
        return new JAXBElement<>(OCORRENCIA_LONGITUDE, String.class, Ocorrencia.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "Observacao", scope = Ocorrencia.class)
    public JAXBElement<String> createOcorrenciaObservacao(String value) {
        return new JAXBElement<>(OCORRENCIA_OBSERVACAO, String.class, Ocorrencia.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "TipoOcorrencia", scope = Ocorrencia.class)
    public JAXBElement<TipoOcorrencia> createOcorrenciaTipoOcorrencia(TipoOcorrencia value) {
        return new JAXBElement<>(OCORRENCIA_TIPO_OCORRENCIA, TipoOcorrencia.class, Ocorrencia.class, value);
    }
}
