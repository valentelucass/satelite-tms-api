package com.example.satelite.vedacit.nfe;

import javax.xml.namespace.QName;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlElementDecl;
import jakarta.xml.bind.annotation.XmlRegistry;

@XmlRegistry
public class ObjectFactory {

    public static final String NAMESPACE = "http://schemas.datacontract.org/2004/07/SGT.WebService.NFe";

    private static final QName CANHOTO_CHAVE_ACESSO = new QName(NAMESPACE, "ChaveAcesso");
    private static final QName CANHOTO_CHAVE_ACESSO_CTE = new QName(NAMESPACE, "ChaveAcessoCte");
    private static final QName CANHOTO_DATA_ENTREGA_NOTA = new QName(NAMESPACE, "DataEntregaNota");
    private static final QName CANHOTO_DATA_ENVIO_CANHOTO = new QName(NAMESPACE, "DataEnvioCanhoto");
    private static final QName CANHOTO_IMAGEM_CANHOTO_BASE64 = new QName(NAMESPACE, "ImagemCanhotoBase64");
    private static final QName CANHOTO_NOME_IMAGEM_CANHOTO = new QName(NAMESPACE, "NomeImagemCanhoto");
    private static final QName CANHOTO_LATITUDE = new QName(NAMESPACE, "Latitude");
    private static final QName CANHOTO_LONGITUDE = new QName(NAMESPACE, "Longitude");
    private static final QName CANHOTO_NUMERO_NOTA_FISCAL = new QName(NAMESPACE, "NumeroNotaFiscal");
    private static final QName CANHOTO_SERIE_NOTA_FISCAL = new QName(NAMESPACE, "SerieNotaFiscal");
    private static final QName CANHOTO_OBSERVACAO = new QName(NAMESPACE, "Observacao");
    private static final QName RETORNO_MENSAGEM = new QName(NAMESPACE, "Mensagem");

    public ObjectFactory() {
    }

    public Canhoto createCanhoto() {
        return new Canhoto();
    }

    public RetornoOfboolean createRetornoOfboolean() {
        return new RetornoOfboolean();
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "ChaveAcesso", scope = Canhoto.class)
    public JAXBElement<String> createCanhotoChaveAcesso(String value) {
        return new JAXBElement<>(CANHOTO_CHAVE_ACESSO, String.class, Canhoto.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "ChaveAcessoCte", scope = Canhoto.class)
    public JAXBElement<String> createCanhotoChaveAcessoCte(String value) {
        return new JAXBElement<>(CANHOTO_CHAVE_ACESSO_CTE, String.class, Canhoto.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "DataEntregaNota", scope = Canhoto.class)
    public JAXBElement<String> createCanhotoDataEntregaNota(String value) {
        return new JAXBElement<>(CANHOTO_DATA_ENTREGA_NOTA, String.class, Canhoto.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "DataEnvioCanhoto", scope = Canhoto.class)
    public JAXBElement<String> createCanhotoDataEnvioCanhoto(String value) {
        return new JAXBElement<>(CANHOTO_DATA_ENVIO_CANHOTO, String.class, Canhoto.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "ImagemCanhotoBase64", scope = Canhoto.class)
    public JAXBElement<String> createCanhotoImagemCanhotoBase64(String value) {
        return new JAXBElement<>(CANHOTO_IMAGEM_CANHOTO_BASE64, String.class, Canhoto.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "NomeImagemCanhoto", scope = Canhoto.class)
    public JAXBElement<String> createCanhotoNomeImagemCanhoto(String value) {
        return new JAXBElement<>(CANHOTO_NOME_IMAGEM_CANHOTO, String.class, Canhoto.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "Latitude", scope = Canhoto.class)
    public JAXBElement<String> createCanhotoLatitude(String value) {
        return new JAXBElement<>(CANHOTO_LATITUDE, String.class, Canhoto.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "Longitude", scope = Canhoto.class)
    public JAXBElement<String> createCanhotoLongitude(String value) {
        return new JAXBElement<>(CANHOTO_LONGITUDE, String.class, Canhoto.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "NumeroNotaFiscal", scope = Canhoto.class)
    public JAXBElement<String> createCanhotoNumeroNotaFiscal(String value) {
        return new JAXBElement<>(CANHOTO_NUMERO_NOTA_FISCAL, String.class, Canhoto.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "SerieNotaFiscal", scope = Canhoto.class)
    public JAXBElement<String> createCanhotoSerieNotaFiscal(String value) {
        return new JAXBElement<>(CANHOTO_SERIE_NOTA_FISCAL, String.class, Canhoto.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "Observacao", scope = Canhoto.class)
    public JAXBElement<String> createCanhotoObservacao(String value) {
        return new JAXBElement<>(CANHOTO_OBSERVACAO, String.class, Canhoto.class, value);
    }

    @XmlElementDecl(namespace = NAMESPACE, name = "Mensagem", scope = RetornoOfboolean.class)
    public JAXBElement<String> createRetornoOfbooleanMensagem(String value) {
        return new JAXBElement<>(RETORNO_MENSAGEM, String.class, RetornoOfboolean.class, value);
    }
}
