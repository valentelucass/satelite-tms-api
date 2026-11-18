package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

class AlvosComprovanteVedacitSoapTest {
    private static String chave(String modelo, String semente) {
        String base=semente.repeat(20)+modelo+semente.repeat(21);
        int soma=0;
        for(int i=42,peso=2;i>=0;i--,peso=peso==9?2:peso+1)soma+=(base.charAt(i)-'0')*peso;
        int dv=11-soma%11;
        return base+(dv>=10?0:dv);
    }
    @Test void parAlteradoPelaReconciliacaoFalhaAntesDaPortaSoap() {
        var service=mock(VedacitIntegrationService.class,CALLS_REAL_METHODS);
        var alvo=new AlvosComprovanteVedacit.Alvo(chave("55","1"),chave("57","2"));
        try(var escopo=AlvosComprovanteVedacit.restringir(alvo)) {
            assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(
                    service,"enviarCanhoto",null,alvo.chaveNfe(),chave("57","3")));
        }
    }
}
