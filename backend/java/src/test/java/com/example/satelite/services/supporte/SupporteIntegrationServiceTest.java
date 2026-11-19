package com.example.satelite.services.supporte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.satelite.clients.SupporteClient;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslItemDTO;
import com.example.satelite.dto.rodogarcia.EslFreightDTO;
import com.example.satelite.dto.rodogarcia.EslInvoiceDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.dto.rodogarcia.EslOccurrenceDefDTO;
import com.example.satelite.dto.supporte.SupporteOcorrenciaRequestDTO;
import com.example.satelite.dto.supporte.SupporteOcorrenciaResponseDTO;
import com.example.satelite.dto.supporte.SupporteRetornoDTO;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.utils.ImageDownloader;

class SupporteIntegrationServiceTest {

    private static final String CHAVE_NFE = "35260745859932000394550010000226901794138359";
    private static final String CHAVE_CTE = "35260726295146000286570070000715321851060770";

    @Test
    void deveEnviarOcorrenciaComBasicECertificarComprovanteBase64() throws Exception {
        SupporteClient client = mock(SupporteClient.class);
        ImageDownloader imageDownloader = mock(ImageDownloader.class);
        SupporteIntegrationService service = configurar(client, imageDownloader);
        when(imageDownloader.baixarImagemDaUrl("https://assinada.exemplo/canhoto.png", CHAVE_CTE))
                .thenReturn(imagemPng());
        when(client.enviarOcorrencia(eq("Basic token-teste"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(resposta("OCORRENCIA PROCESSADA COM SUCESSO - COMPROVANTE RECEBIDO", true)));

        ResultadoIntegracao resultado = service.processarOcorrencia(criarOcorrencia(1), criarComprovante());

        ArgumentCaptor<SupporteOcorrenciaRequestDTO> captor = ArgumentCaptor.forClass(SupporteOcorrenciaRequestDTO.class);
        verify(client).enviarOcorrencia(eq("Basic token-teste"), captor.capture());
        SupporteOcorrenciaRequestDTO payload = captor.getValue();
        assertEquals("19109840000468", payload.cnpjTransportadora());
        assertEquals("26295146000286", payload.cnpjPagador());
        assertEquals(1, payload.nf().serieNfe());
        assertEquals(22690, payload.nf().numeroNfe());
        assertEquals(7, payload.cte().serieCte());
        assertEquals(71532, payload.cte().numeroCte());
        assertEquals("17-06-2026 10:30:00", payload.evento().dataHoraEvento());
        assertEquals(1, payload.evento().codigo());
        assertEquals("Entrega Realizada", payload.evento().descricao());
        org.junit.jupiter.api.Assertions.assertTrue(payload.evento().imagemComprovante().startsWith("data:image/jpeg;base64,"));
        assertEquals(ResultadoIntegracao.STATUS_ENVIADO, resultado.status());
    }

    @Test
    void deveManterPendenteDeComprovanteQuandoOcorrenciaForAceitaSemImagem() throws Exception {
        SupporteClient client = mock(SupporteClient.class);
        ImageDownloader imageDownloader = mock(ImageDownloader.class);
        SupporteIntegrationService service = configurar(client, imageDownloader);
        when(client.enviarOcorrencia(eq("Basic token-teste"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(resposta("OCORRENCIA PROCESSADA COM SUCESSO", false)));

        ResultadoIntegracao resultado = service.processarOcorrencia(criarOcorrencia(1), null);

        ArgumentCaptor<SupporteOcorrenciaRequestDTO> captor = ArgumentCaptor.forClass(SupporteOcorrenciaRequestDTO.class);
        verify(client).enviarOcorrencia(eq("Basic token-teste"), captor.capture());
        assertNull(captor.getValue().evento().imagemComprovante());
        assertEquals(
                JsonInclude.Include.NON_NULL,
                captor.getValue().evento().getClass().getAnnotation(JsonInclude.class).value()
        );
        assertEquals(ResultadoIntegracao.STATUS_PARCIAL, resultado.status());
        assertEquals(ResultadoIntegracao.STATUS_PENDENTE_FOTO, resultado.statusCanhoto());
    }

    @Test
    void deveRecusarOcorrenciaQueNaoSejaEntregaRealizada() {
        SupporteIntegrationService service = configurar(mock(SupporteClient.class), mock(ImageDownloader.class));

        assertThrows(IllegalStateException.class, () -> service.processarOcorrencia(criarOcorrencia(2), null));
    }

    @Test
    void deveConciliarDocumentoJaFinalizadoComoIdempotente() {
        SupporteClient client = mock(SupporteClient.class);
        SupporteIntegrationService service = configurar(client, mock(ImageDownloader.class));
        when(client.enviarOcorrencia(eq("Basic token-teste"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(resposta("DOCUMENTO JA FOI FINALIZADO - COMPROVANTE RECEBIDO", true)));

        ResultadoIntegracao resultado = service.processarOcorrencia(criarOcorrencia(1), null);

        assertEquals(ResultadoIntegracao.STATUS_ENVIADO, resultado.status());
    }

    @Test
    void deveFiltrarNfeForaDasFiliaisSupporte() {
        SupporteIntegrationService service = configurar(mock(SupporteClient.class), mock(ImageDownloader.class));
        EslOcorrenciaDTO ocorrencia = new EslOcorrenciaDTO(
                10L,
                OffsetDateTime.parse("2026-06-17T10:30:00-03:00"),
                new EslInvoiceDTO(20L, "35260612345678000123550010000012341000012345", "1", "1234"),
                new EslFreightDTO(30L, "35260612345678000123570010000012341000012345"),
                new EslOccurrenceDefDTO(40L, 1, "Entrega Realizada")
        );

        org.junit.jupiter.api.Assertions.assertFalse(service.notaFiscalPermitida(ocorrencia));
    }

    private SupporteIntegrationService configurar(SupporteClient client, ImageDownloader imageDownloader) {
        SupporteIntegrationService service = new SupporteIntegrationService(client, imageDownloader);
        ReflectionTestUtils.setField(service, "authorization", "Basic token-teste");
        ReflectionTestUtils.setField(service, "cnpjTransportadora", "19109840000468");
        ReflectionTestUtils.setField(service, "cnpjPagadores", "26295146000448,26295146000286,03447983000105");
        return service;
    }

    private EslOcorrenciaDTO criarOcorrencia(int codigo) {
        return new EslOcorrenciaDTO(
                10L,
                OffsetDateTime.parse("2026-06-17T10:30:00-03:00"),
                new EslInvoiceDTO(20L, CHAVE_NFE, "1", "22690"),
                new EslFreightDTO(30L, CHAVE_CTE),
                new EslOccurrenceDefDTO(40L, codigo, "Entrega Realizada")
        );
    }

    private ComprovanteEslDTO criarComprovante() {
        return new ComprovanteEslDTO(List.of(new ComprovanteEslItemDTO(
                50L,
                "https://assinada.exemplo/canhoto.png",
                null,
                null,
                null
        )), null);
    }

    private SupporteOcorrenciaResponseDTO resposta(String descricao, boolean comprovanteRecebido) {
        return new SupporteOcorrenciaResponseDTO(new SupporteRetornoDTO(
                200,
                descricao,
                1234,
                null,
                null,
                "protocolo-teste",
                comprovanteRecebido
        ));
    }

    private byte[] imagemPng() throws Exception {
        BufferedImage imagem = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        imagem.setRGB(0, 0, 0xFF_11_22_33);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(imagem, "png", output);
            return output.toByteArray();
        }
    }
}
