package com.example.satelite.services.ppg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.satelite.clients.PpgClient;
import com.example.satelite.dto.ppg.PpgOcorrenciaRequestDTO;
import com.example.satelite.dto.ppg.PpgOcorrenciaResponseDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslItemDTO;
import com.example.satelite.dto.rodogarcia.EslFreightDTO;
import com.example.satelite.dto.rodogarcia.EslInvoiceDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.dto.rodogarcia.EslOccurrenceDefDTO;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.utils.ImageDownloader;
import com.example.satelite.utils.ImageUtils;

class PpgIntegrationServiceTest {

    @Test
    void deveEnviarOcorrenciaComImagemRealDoComprovante() throws Exception {
        PpgClient ppgClient = mock(PpgClient.class);
        PpgAuthService ppgAuthService = mock(PpgAuthService.class);
        ImageDownloader imageDownloader = mock(ImageDownloader.class);
        ImageUtils imageUtils = mock(ImageUtils.class);

        PpgIntegrationService service = new PpgIntegrationService(
                ppgClient,
                ppgAuthService,
                imageDownloader,
                imageUtils
        );
        ReflectionTestUtils.setField(service, "entregadorId", 123);
        ReflectionTestUtils.setField(service, "cnpjTransportadora", "12345678000199");

        byte[] imagemOriginal = new byte[] { 1, 2, 3 };
        String imagemPpg = "data:image/jpeg;base64,BASE64_REAL";

        when(imageDownloader.baixarImagemDaUrl(
                "https://assinada.exemplo/canhoto.jpg",
                "35260612345678000123570010000012341000012345"
        )).thenReturn(imagemOriginal);
        when(imageUtils.converterParaBase64Ppg(imagemOriginal)).thenReturn(imagemPpg);
        when(ppgAuthService.obterTokenValido()).thenReturn("token-ppg");
        when(ppgClient.enviarOcorrencia(eq("token-ppg"), any(PpgOcorrenciaRequestDTO.class)))
                .thenReturn(new PpgOcorrenciaResponseDTO(
                        "35260612345678000123550010000012341000012345",
                        "F123445",
                        "01 - Comprovante em análise",
                        null,
                        null
                ));

        ResultadoIntegracao resultado = service.processarOcorrencia(criarOcorrencia(), criarComprovante());

        ArgumentCaptor<PpgOcorrenciaRequestDTO> payloadCaptor = ArgumentCaptor.forClass(PpgOcorrenciaRequestDTO.class);
        verify(ppgClient).enviarOcorrencia(eq("token-ppg"), payloadCaptor.capture());

        assertEquals(ResultadoIntegracao.STATUS_ENVIADO, resultado.status());
        assertEquals(ResultadoIntegracao.STATUS_SUCESSO, resultado.statusDados());
        assertEquals(ResultadoIntegracao.STATUS_SUCESSO, resultado.statusCanhoto());

        PpgOcorrenciaRequestDTO payload = payloadCaptor.getValue();
        assertEquals("35260612345678000123550010000012341000012345", payload.documento());
        assertEquals(1, payload.tipoOcorrenciaId());
        assertEquals("F", payload.tipoEntrega());
        assertEquals("12345678000199", payload.cnpjTransportadora());
        assertEquals(123, payload.entregadorId());
        assertEquals("2026-06-17T13:30:00.000Z", payload.dataEntrega());
        assertEquals("2026-06-17T13:30:00.000Z", payload.dataRegistro());
        assertEquals("I", payload.tipoEntrada());
        assertEquals("0", payload.latitude());
        assertEquals("0", payload.longitude());
        assertEquals(imagemPpg, payload.ocorrenciaEntregaFoto().get(0).foto());
        assertEquals("C", payload.ocorrenciaEntregaFoto().get(0).tipoFoto());
    }

    private EslOcorrenciaDTO criarOcorrencia() {
        return new EslOcorrenciaDTO(
                10L,
                OffsetDateTime.parse("2026-06-17T10:30:00-03:00"),
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
