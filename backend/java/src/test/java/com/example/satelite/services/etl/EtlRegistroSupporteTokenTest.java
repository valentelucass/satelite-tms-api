package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.example.satelite.clients.RodogarciaClient;

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
    void deveManterTokenClienteVedacitQuandoNaoHouverTokenExclusivo() {
        EtlRegistroService service = criarService();

        assertEquals(
                "Bearer token-ocorrencia-vedacit",
                service.obterHeaderComprovante("VEDACIT", "Bearer token-ocorrencia-vedacit")
        );
    }

    @Test
    void comprovanteVedacitSemTokenDocumentalUsaRotaCliente() {
        var client = mock(RodogarciaClient.class);
        var service = new EtlRegistroService(client, null, null, null, null, null, null, null);
        service.consultarComprovante("VEDACIT", "Bearer token-vedacit", "cte-exato");
        verify(client).buscarComprovanteCliente("Bearer token-vedacit", "cte-exato");
        verifyNoMoreInteractions(client);
    }

    @Test
    void credencialDocumentalExplicitaMantemContratoGeral() {
        var client = mock(RodogarciaClient.class);
        var service = new EtlRegistroService(client, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "tokenVedacitComprovanteEsl", "token-documental");
        service.consultarComprovante("VEDACIT", "Bearer token-vedacit", "cte-exato");
        verify(client).buscarComprovante("Bearer token-documental", "cte-exato");
        verifyNoMoreInteractions(client);
    }

    @Test
    void outrosClientesMantemContratoAnterior() {
        var client = mock(RodogarciaClient.class);
        var service = new EtlRegistroService(client, null, null, null, null, null, null, null);
        service.consultarComprovante("PPG", "Bearer token-ppg", "cte-ppg");
        verify(client).buscarComprovante("Bearer token-ppg", "cte-ppg");
        verifyNoMoreInteractions(client);
    }

    private EtlRegistroService criarService() {
        return new EtlRegistroService(null, null, null, null, null, null, null, null);
    }
}
