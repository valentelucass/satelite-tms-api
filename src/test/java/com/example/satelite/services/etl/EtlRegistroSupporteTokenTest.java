package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EtlRegistroSupporteTokenTest {

    @Test
    void deveUsarTokenExclusivoDeComprovanteParaSupporte() {
        EtlRegistroService service = criarService();
        ReflectionTestUtils.setField(service, "tokenSupporteComprovanteEsl", "token-comprovante-supporte");

        assertEquals(
                "Bearer token-comprovante-supporte",
                service.obterHeaderComprovante("SUPPORTE", "Bearer token-ocorrencia-supporte")
        );
    }

    @Test
    void deveManterTokenDaOcorrenciaQuandoTokenDeComprovanteSupporteEstiverAusente() {
        EtlRegistroService service = criarService();

        assertEquals(
                "Bearer token-ocorrencia-supporte",
                service.obterHeaderComprovante("SUPPORTE", "Bearer token-ocorrencia-supporte")
        );
    }

    private EtlRegistroService criarService() {
        return new EtlRegistroService(null, null, null, null, null, null, null, null);
    }
}
