package org.tempuri;

import org.datacontract.schemas._2004._07.dominio_objetosdevalor_embarcador.Ocorrencia;
import org.datacontract.schemas._2004._07.sgt.RetornoOfint;

import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;
import jakarta.xml.bind.annotation.XmlSeeAlso;

@WebService(name = "IOcorrencias", targetNamespace = IOcorrencias.NAMESPACE)
@SOAPBinding(parameterStyle = SOAPBinding.ParameterStyle.WRAPPED)
@XmlSeeAlso({
        org.datacontract.schemas._2004._07.dominio_objetosdevalor_embarcador.ObjectFactory.class,
        org.datacontract.schemas._2004._07.sgt.ObjectFactory.class
})
public interface IOcorrencias {

    String NAMESPACE = "http://tempuri.org/";

    @WebMethod(operationName = "AdicionarOcorrencia", action = "http://tempuri.org/IOcorrencias/AdicionarOcorrencia")
    @WebResult(name = "AdicionarOcorrenciaResult", targetNamespace = NAMESPACE)
    RetornoOfint adicionarOcorrencia(
            @WebParam(name = "ocorrencia", targetNamespace = NAMESPACE) Ocorrencia ocorrencia
    );
}
