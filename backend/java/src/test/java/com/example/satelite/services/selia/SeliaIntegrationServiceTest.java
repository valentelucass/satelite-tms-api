package com.example.satelite.services.selia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.satelite.clients.SeliaClient;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslItemDTO;
import com.example.satelite.dto.rodogarcia.EslFreightDTO;
import com.example.satelite.dto.rodogarcia.EslInvoiceDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.dto.rodogarcia.EslOccurrenceDefDTO;
import com.example.satelite.dto.selia.SeliaAddEventsRequestDTO;
import com.example.satelite.services.ResultadoIntegracao;

class SeliaIntegrationServiceTest {

    @Test
    void deveEnviarEventoComPedidoEVolumeIndependentesEComprovantePod() {
        SeliaClient client = mock(SeliaClient.class);
        SeliaPlpCorrelationService correlationService = mock(SeliaPlpCorrelationService.class);
        SeliaIntegrationService service = new SeliaIntegrationService(client, correlationService);
        configurar(service);

        ResultadoIntegracao resultado = service.processarOcorrencia(criarOcorrencia(), criarComprovante());

        ArgumentCaptor<SeliaAddEventsRequestDTO> payloadCaptor =
                ArgumentCaptor.forClass(SeliaAddEventsRequestDTO.class);
        verify(client).adicionarEventos(
                eq("api-key-teste"),
                eq("lp-key-teste"),
                eq("SATELITE_TMS"),
                eq("1.0.0"),
                eq("RODOGARCIA_INTELIPOST"),
                eq("1.0.0"),
                payloadCaptor.capture()
        );

        SeliaAddEventsRequestDTO payload = payloadCaptor.getValue();
        assertEquals("PEDIDO-123", payload.orderNumber());
        assertEquals("VOLUME-456", payload.volumeNumber());
        assertEquals("35260612345678000123550010000012341000012345", payload.invoiceKey());
        assertEquals("2026-06-17T10:30-03:00", payload.events().get(0).eventDate());
        assertEquals("1", payload.events().get(0).originalCode());
        assertEquals("https://assinada.exemplo/canhoto.jpg", payload.events().get(0).attachments().get(0).url());
        assertEquals("POD", payload.events().get(0).attachments().get(0).type());
        assertEquals(ResultadoIntegracao.STATUS_ENVIADO, resultado.status());
    }

    @Test
    void deveUsarMapeamentoPlpQuandoOcorrenciaEslNaoTrouxerPedidoEVolume() {
        SeliaClient client = mock(SeliaClient.class);
        SeliaPlpCorrelationService correlationService = mock(SeliaPlpCorrelationService.class);
        when(correlationService.buscarPorChaveNfe("35260612345678000123550010000012341000012345"))
                .thenReturn(List.of(
                        new SeliaPlpCorrelationService.IdentificacaoEntrega("PEDIDO-PLP", "VOLUME-1"),
                        new SeliaPlpCorrelationService.IdentificacaoEntrega("PEDIDO-PLP", "VOLUME-2")
                ));
        SeliaIntegrationService service = new SeliaIntegrationService(client, correlationService);
        configurar(service);

        service.processarOcorrencia(criarOcorrenciaSemPedidoEVolume(), criarComprovante());

        ArgumentCaptor<SeliaAddEventsRequestDTO> payloadCaptor =
                ArgumentCaptor.forClass(SeliaAddEventsRequestDTO.class);
        verify(client, times(2)).adicionarEventos(
                eq("api-key-teste"),
                eq("lp-key-teste"),
                eq("SATELITE_TMS"),
                eq("1.0.0"),
                eq("RODOGARCIA_INTELIPOST"),
                eq("1.0.0"),
                payloadCaptor.capture()
        );
        List<String> volumes = payloadCaptor.getAllValues().stream()
                .map(payload -> {
                    assertNotNull(payload);
                    return payload.volumeNumber();
                })
                .toList();
        assertEquals(List.of("VOLUME-1", "VOLUME-2"), volumes);
    }

    @Test
    void deveEnviarEventoIntermediarioMapeadoSemPod() {
        SeliaClient client = mock(SeliaClient.class);
        SeliaPlpCorrelationService correlationService = mock(SeliaPlpCorrelationService.class);
        SeliaIntegrationService service = new SeliaIntegrationService(client, correlationService);
        configurar(service);
        ReflectionTestUtils.setField(service, "eventCodeMap", "55=85");

        ResultadoIntegracao resultado = service.processarOcorrencia(criarOcorrencia(55, "Saida para Entrega"), null);

        ArgumentCaptor<SeliaAddEventsRequestDTO> payloadCaptor =
                ArgumentCaptor.forClass(SeliaAddEventsRequestDTO.class);
        verify(client).adicionarEventos(
                eq("api-key-teste"), eq("lp-key-teste"), eq("SATELITE_TMS"), eq("1.0.0"),
                eq("RODOGARCIA_INTELIPOST"), eq("1.0.0"), payloadCaptor.capture()
        );
        assertEquals("85", payloadCaptor.getValue().events().get(0).originalCode());
        assertEquals(List.of(), payloadCaptor.getValue().events().get(0).attachments());
        assertEquals(ResultadoIntegracao.STATUS_ENVIADO, resultado.status());
    }

    @Test
    void naoDeveEnviarEventoSemDeParaConfigurado() {
        SeliaClient client = mock(SeliaClient.class);
        SeliaPlpCorrelationService correlationService = mock(SeliaPlpCorrelationService.class);
        SeliaIntegrationService service = new SeliaIntegrationService(client, correlationService);
        configurar(service);

        ResultadoIntegracao resultado = service.processarOcorrencia(criarOcorrencia(55, "Saida para Entrega"), null);

        assertEquals(ResultadoIntegracao.STATUS_IGNORADO, resultado.status());
        verify(client, never()).adicionarEventos(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(SeliaAddEventsRequestDTO.class)
        );
    }

    @Test
    void deveAplicarDeParaHomologadoSemAnexoParaEmissaoCte() {
        SeliaClient client = mock(SeliaClient.class);
        SeliaPlpCorrelationService correlationService = mock(SeliaPlpCorrelationService.class);
        SeliaIntegrationService service = new SeliaIntegrationService(client, correlationService);
        configurar(service);
        ReflectionTestUtils.setField(service, "eventCodeMap", mapaDeParaOficial());

        service.processarOcorrencia(criarOcorrencia(110, "CTE Emitido"), null);

        ArgumentCaptor<SeliaAddEventsRequestDTO> payloadCaptor =
                ArgumentCaptor.forClass(SeliaAddEventsRequestDTO.class);
        verify(client).adicionarEventos(
                eq("api-key-teste"), eq("lp-key-teste"), eq("SATELITE_TMS"), eq("1.0.0"),
                eq("RODOGARCIA_INTELIPOST"), eq("1.0.0"), payloadCaptor.capture()
        );
        assertEquals("80", payloadCaptor.getValue().events().get(0).originalCode());
        assertEquals(List.of(), payloadCaptor.getValue().events().get(0).attachments());
    }

    private void configurar(SeliaIntegrationService service) {
        ReflectionTestUtils.setField(service, "apiKey", "api-key-teste");
        ReflectionTestUtils.setField(service, "logisticProviderApiKey", "lp-key-teste");
        ReflectionTestUtils.setField(service, "platform", "SATELITE_TMS");
        ReflectionTestUtils.setField(service, "platformVersion", "1.0.0");
        ReflectionTestUtils.setField(service, "plugin", "RODOGARCIA_INTELIPOST");
        ReflectionTestUtils.setField(service, "pluginVersion", "1.0.0");
        ReflectionTestUtils.setField(service, "deliveryEventCode", "1");
        ReflectionTestUtils.setField(service, "eventCodeMap", "");
        ReflectionTestUtils.setField(service, "allEventsEnabled", false);
        ReflectionTestUtils.setField(service, "receiptType", "POD");
        ReflectionTestUtils.setField(service, "receiptMimeType", "image/jpeg");
    }

    private String mapaDeParaOficial() {
        return "2=72,6=10,14=45,25=3,27=55,46=11,47=57,52=18,55=85,58=59,59=9,76=60,"
                + "79=53,85=61,91=15,101=7,103=58,110=80,135=62";
    }

    private EslOcorrenciaDTO criarOcorrencia() {
        return criarOcorrencia(1, "Entrega Realizada");
    }

    private EslOcorrenciaDTO criarOcorrencia(int codigo, String descricao) {
        return new EslOcorrenciaDTO(
                10L,
                "PEDIDO-123",
                "VOLUME-456",
                OffsetDateTime.parse("2026-06-17T10:30:00-03:00"),
                null,
                new EslInvoiceDTO(20L, "35260612345678000123550010000012341000012345", "1", "1234"),
                new EslFreightDTO(30L, "35260612345678000123570010000012341000012345"),
                new EslOccurrenceDefDTO(40L, codigo, descricao)
        );
    }

    private EslOcorrenciaDTO criarOcorrenciaSemPedidoEVolume() {
        return new EslOcorrenciaDTO(
                10L,
                OffsetDateTime.parse("2026-06-17T10:30:00-03:00"),
                null,
                new EslInvoiceDTO(20L, "35260612345678000123550010000012341000012345", "1", "1234"),
                new EslFreightDTO(30L, "35260612345678000123570010000012341000012345"),
                new EslOccurrenceDefDTO(40L, 1, "Entrega Realizada")
        );
    }

    private ComprovanteEslDTO criarComprovante() {
        ComprovanteEslItemDTO item = new ComprovanteEslItemDTO(
                50L,
                "https://assinada.exemplo/canhoto.jpg",
                OffsetDateTime.parse("2026-06-17T10:31:00-03:00"),
                OffsetDateTime.parse("2026-06-17T10:32:00-03:00"),
                null
        );
        return new ComprovanteEslDTO(List.of(item), null);
    }
}
