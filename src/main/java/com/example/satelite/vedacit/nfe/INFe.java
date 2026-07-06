package com.example.satelite.vedacit.nfe;

import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;
import jakarta.xml.bind.annotation.XmlSeeAlso;

@WebService(name = "INFe", targetNamespace = INFe.NAMESPACE)
@SOAPBinding(parameterStyle = SOAPBinding.ParameterStyle.WRAPPED)
@XmlSeeAlso(ObjectFactory.class)
public interface INFe {

    String NAMESPACE = "http://tempuri.org/";

    @WebMethod(operationName = "EnviarDigitalizacaoCanhoto", action = "http://tempuri.org/INFe/EnviarDigitalizacaoCanhoto")
    @WebResult(name = "EnviarDigitalizacaoCanhotoResult", targetNamespace = NAMESPACE)
    RetornoOfboolean enviarDigitalizacaoCanhoto(
            @WebParam(name = "canhoto", targetNamespace = NAMESPACE) Canhoto canhoto
    );
}
