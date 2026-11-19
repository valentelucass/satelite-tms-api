package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslFreightDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslItemDTO;

import feign.FeignException;
import feign.Request;
import feign.Response;

class EtlRegistroSeliaComprovanteTest {
    private static final String CTE = "1".repeat(44);
    private static final String AUTH = "Bearer token-cliente-selia";
    private final RodogarciaClient client = mock(RodogarciaClient.class);
    private final EtlRegistroService service = new EtlRegistroService(client, null, null, null, null, null, null, null);

    @Test
    void usaRotaClienteComTokenSeliaDasOcorrencias() {
        var item = item(CTE, "https://example.invalid/canhoto.jpg");
        when(client.buscarComprovanteCliente(AUTH, CTE)).thenReturn(new ComprovanteEslDTO(List.of(item), null));

        assertEquals(List.of(item), service.consultarComprovante("SELIA", AUTH, CTE).data());
        assertEquals(AUTH, service.obterHeaderComprovante("SELIA", AUTH));
        verify(client).buscarComprovanteCliente(AUTH, CTE);
        verifyNoMoreInteractions(client);
    }

    @Test
    void selecionaCteExatoMesmoQuandoOutroComprovanteVemPrimeiro() {
        var correto = item(CTE, "https://example.invalid/correto.jpg");
        var outro = item("2".repeat(44), "https://example.invalid/outro.jpg");
        when(client.buscarComprovanteCliente(AUTH, CTE)).thenReturn(
                new ComprovanteEslDTO(List.of(outro, correto), null));

        assertEquals(List.of(correto), service.consultarComprovante("SELIA", AUTH, CTE).data());
    }

    @Test
    void naoLiberaComprovanteDivergenteSemIdentidadeOuSemImagem() {
        var semFrete = new ComprovanteEslItemDTO(1L, "https://example.invalid/sem-cte.jpg", null, null, null);
        when(client.buscarComprovanteCliente(AUTH, CTE)).thenReturn(new ComprovanteEslDTO(Arrays.asList(
                null, semFrete, item(null, "https://example.invalid/sem-chave.jpg"),
                item("2".repeat(44), "https://example.invalid/outro.jpg"), item(CTE, null), item(CTE, " ")), null));

        assertTrue(service.consultarComprovante("SELIA", AUTH, CTE).data().isEmpty());
    }

    @Test
    void respostaVaziaPermaneceSemComprovante() {
        when(client.buscarComprovanteCliente(AUTH, CTE)).thenReturn(new ComprovanteEslDTO(List.of(), null));
        assertTrue(service.consultarComprovante("SELIA", AUTH, CTE).data().isEmpty());
    }

    @Test
    void toleraRespostaNulaOuListaAusente() {
        when(client.buscarComprovanteCliente(AUTH, CTE)).thenReturn(null, new ComprovanteEslDTO(null, null));
        assertNull(service.consultarComprovante("SELIA", AUTH, CTE));
        assertNull(service.consultarComprovante("SELIA", AUTH, CTE).data());
    }

    @Test
    void recusaDeAcessoNaoDisparaFallbackParaOutroContrato() {
        var request = Request.create(Request.HttpMethod.GET, "https://example.invalid/receipts", Map.of(), null, null, null);
        var failure = FeignException.errorStatus("buscarComprovanteCliente",
                Response.builder().request(request).status(401).reason("Unauthorized").headers(Map.of()).build());
        when(client.buscarComprovanteCliente(AUTH, CTE)).thenThrow(failure);

        assertSame(failure, assertThrows(FeignException.class, () -> service.consultarComprovante("SELIA", AUTH, CTE)));
        verify(client).buscarComprovanteCliente(AUTH, CTE);
        verifyNoMoreInteractions(client);
    }

    private static ComprovanteEslItemDTO item(String cte, String image) {
        return new ComprovanteEslItemDTO(1L, image, null, null, new ComprovanteEslFreightDTO(2L, 3L, cte, null));
    }
}
