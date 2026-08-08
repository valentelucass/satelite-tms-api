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

    @Test
    void deveUsarTokenExclusivoDeComprovanteParaVedacit() {
        EtlRegistroService service = criarService();
        ReflectionTestUtils.setField(service, "tokenVedacitComprovanteEsl", "token-comprovante-vedacit");

        assertEquals(
                "Bearer token-comprovante-vedacit",
                service.obterHeaderComprovante("VEDACIT", "Bearer token-ocorrencia-vedacit")
        );
    }

    @Test
    void deveUsarTokenMasterParaComprovanteVedacitQuandoNaoHouverTokenExclusivo() {
        EtlRegistroService service = criarService();
        ReflectionTestUtils.setField(service, "tokenMasterEsl", "token-master-esl");

        assertEquals(
                "Bearer token-master-esl",
                service.obterHeaderComprovante("VEDACIT", "Bearer token-ocorrencia-vedacit")
        );
    }

    private EtlRegistroService criarService() {
        return new EtlRegistroService(null, null, null, null, null, null, null, null);
    }
}
