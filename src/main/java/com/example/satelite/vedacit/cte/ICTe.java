package com.example.satelite.vedacit.cte;

import com.example.satelite.vedacit.cte.sgt.RetornoOfstring;

import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;
import jakarta.xml.bind.annotation.XmlSeeAlso;

@WebService(name = "ICTe", targetNamespace = ICTe.NAMESPACE)
@SOAPBinding(parameterStyle = SOAPBinding.ParameterStyle.WRAPPED)
@XmlSeeAlso(com.example.satelite.vedacit.cte.sgt.ObjectFactory.class)
public interface ICTe {

    String NAMESPACE = "http://tempuri.org/";

    @WebMethod(operationName = "EnviarArquivoXMLCTe", action = "http://tempuri.org/ICTe/EnviarArquivoXMLCTe")
    @WebResult(name = "EnviarArquivoXMLCTeResult", targetNamespace = NAMESPACE)
    RetornoOfstring enviarArquivoXMLCTe(
            @WebParam(name = "arquivo", targetNamespace = NAMESPACE) byte[] arquivo
    );
}
