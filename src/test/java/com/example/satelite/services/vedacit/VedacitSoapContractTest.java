package com.example.satelite.services.vedacit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Cria proxies reais com os snapshots locais, sem invocar operacoes no cliente. */
@Timeout(90)
class VedacitSoapContractTest {
    @Test
    void criaProxyOcorrenciasComContratoLocalCompleto() {
        var service = new org.tempuri.Ocorrencias(wsdl("ocorrencias/Ocorrencias.wsdl"));
        assertNotNull(service.getBasicHttpBindingIOcorrencias());
    }

    @Test
    void criaProxyNfeComContratoLocal() {
        var service = new com.example.satelite.vedacit.nfe.NFe(wsdl("nfe/NFe.wsdl"));
        assertNotNull(service.getBasicHttpBindingINFe());
    }

    @Test
    void criaProxyCteComContratoLocal() {
        var service = new com.example.satelite.vedacit.cte.CTe(wsdl("cte/CTe.wsdl"));
        assertNotNull(service.getBasicHttpBindingICTe());
    }

    private URL wsdl(String path) {
        URL url = getClass().getResource("/wsdl/vedacit/" + path);
        assertNotNull(url);
        return url;
    }
}
