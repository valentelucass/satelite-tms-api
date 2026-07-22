package com.example.satelite.services.selia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    void deveEnviarEventoComPedidoComoVolumeEComprovantePod() {
        SeliaClient client = mock(SeliaClient.class);
        SeliaIntegrationService service = new SeliaIntegrationService(client);
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
        assertEquals("PEDIDO-123", payload.volumeNumber());
        assertEquals("35260612345678000123550010000012341000012345", payload.invoiceKey());
        assertEquals("2026-06-17T10:30-03:00", payload.events().get(0).eventDate());
        assertEquals("14", payload.events().get(0).originalCode());
        assertEquals("https://assinada.exemplo/canhoto.jpg", payload.events().get(0).attachments().get(0).url());
        assertEquals("POD", payload.events().get(0).attachments().get(0).type());
        assertEquals(ResultadoIntegracao.STATUS_ENVIADO, resultado.status());
    }

    private void configurar(SeliaIntegrationService service) {
        ReflectionTestUtils.setField(service, "apiKey", "api-key-teste");
        ReflectionTestUtils.setField(service, "logisticProviderApiKey", "lp-key-teste");
        ReflectionTestUtils.setField(service, "platform", "SATELITE_TMS");
        ReflectionTestUtils.setField(service, "platformVersion", "1.0.0");
        ReflectionTestUtils.setField(service, "plugin", "RODOGARCIA_INTELIPOST");
        ReflectionTestUtils.setField(service, "pluginVersion", "1.0.0");
        ReflectionTestUtils.setField(service, "deliveryEventCode", "14");
        ReflectionTestUtils.setField(service, "receiptType", "POD");
        ReflectionTestUtils.setField(service, "receiptMimeType", "image/jpeg");
    }

    private EslOcorrenciaDTO criarOcorrencia() {
        return new EslOcorrenciaDTO(
                10L,
                "PEDIDO-123",
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
