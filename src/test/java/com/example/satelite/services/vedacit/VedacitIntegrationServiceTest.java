package com.example.satelite.services.vedacit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.EslFreightDTO;
import com.example.satelite.dto.rodogarcia.EslInvoiceDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.dto.rodogarcia.EslOccurrenceDefDTO;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.utils.ImageDownloader;

class VedacitIntegrationServiceTest {

    @Test
    void deveManterCanhotoPendenteQuandoDadosJaForamEnviadosEFotoNaoExiste() {
        VedacitIntegrationService service = new VedacitIntegrationService(
                mock(ImageDownloader.class),
                mock(RodogarciaClient.class)
        );
        ReflectionTestUtils.setField(service, "envioCanhotoHabilitado", true);

        ResultadoIntegracao resultado = service.processarOcorrencia(
                criarOcorrencia(),
                null,
                true,
                false
        );

        assertEquals(ResultadoIntegracao.STATUS_PARCIAL, resultado.status());
        assertEquals(ResultadoIntegracao.STATUS_SUCESSO, resultado.statusDados());
        assertEquals(ResultadoIntegracao.STATUS_PENDENTE_FOTO, resultado.statusCanhoto());
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
}
