package com.example.satelite.services.vedacit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import javax.xml.namespace.QName;

import jakarta.xml.soap.SOAPFactory;
import jakarta.xml.ws.soap.SOAPFaultException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslItemDTO;
import com.example.satelite.dto.rodogarcia.CteDataDTO;
import com.example.satelite.dto.rodogarcia.CteItemDTO;
import com.example.satelite.dto.rodogarcia.CteResponseDTO;
import com.example.satelite.dto.rodogarcia.EslFreightDTO;
import com.example.satelite.dto.rodogarcia.EslInvoiceDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.dto.rodogarcia.EslOccurrenceDefDTO;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.utils.ImageDownloader;
import com.example.satelite.vedacit.cte.ICTe;
import com.example.satelite.vedacit.nfe.Canhoto;
import com.example.satelite.vedacit.nfe.INFe;

import org.datacontract.schemas._2004._07.dominio_objetosdevalor_embarcador.Ocorrencia;
import org.tempuri.IOcorrencias;

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

    @Test
    void deveConciliarDuplicidadeSoapNaOcorrenciaComoDadosEnviados() throws Exception {
        IOcorrencias portaOcorrencias = mock(IOcorrencias.class);
        when(portaOcorrencias.adicionarOcorrencia(any(Ocorrencia.class)))
                .thenThrow(criarErroSoap("Ocorrência já cadastrada"));

        VedacitIntegrationService service = new VedacitIntegrationService(
                mock(ImageDownloader.class),
                mock(RodogarciaClient.class)
        ) {
            @Override
            protected IOcorrencias criarPortaOcorrencias() {
                return portaOcorrencias;
            }
        };
        ReflectionTestUtils.setField(service, "envioOcorrenciaHabilitado", true);
        ReflectionTestUtils.setField(service, "envioXmlCteHabilitado", false);
        ReflectionTestUtils.setField(service, "envioCanhotoHabilitado", false);

        ResultadoIntegracao resultado = service.processarOcorrencia(criarOcorrencia(), null);

        assertEquals(ResultadoIntegracao.STATUS_ENVIADO, resultado.status());
        assertEquals(ResultadoIntegracao.STATUS_SUCESSO, resultado.statusDados());
        assertEquals(ResultadoIntegracao.STATUS_NAO_APLICAVEL, resultado.statusCanhoto());
    }

    @Test
    void deveConciliarDuplicidadeSoapNoXmlCteComoDadosEnviados() throws Exception {
        RodogarciaClient rodogarciaClient = mock(RodogarciaClient.class);
        ICTe portaCte = mock(ICTe.class);

        when(rodogarciaClient.buscarXmlCte(
                "Bearer token-cte",
                "35260612345678000123570010000012341000012345"
        )).thenReturn(new CteResponseDTO(List.of(new CteDataDTO(new CteItemDTO(1L, "autorizado", "<cte/>")))));
        when(portaCte.enviarArquivoXMLCTe(any(byte[].class)))
                .thenThrow(criarErroSoap("Carga já existe"));

        VedacitIntegrationService service = new VedacitIntegrationService(
                mock(ImageDownloader.class),
                rodogarciaClient
        ) {
            @Override
            protected ICTe criarPortaCte() {
                return portaCte;
            }
        };
        ReflectionTestUtils.setField(service, "tokenCteXmlEsl", "token-cte");
        ReflectionTestUtils.setField(service, "envioOcorrenciaHabilitado", false);
        ReflectionTestUtils.setField(service, "envioXmlCteHabilitado", true);
        ReflectionTestUtils.setField(service, "envioCanhotoHabilitado", false);

        ResultadoIntegracao resultado = service.processarOcorrencia(criarOcorrencia(), null);

        assertEquals(ResultadoIntegracao.STATUS_ENVIADO, resultado.status());
        assertEquals(ResultadoIntegracao.STATUS_SUCESSO, resultado.statusDados());
        assertEquals(ResultadoIntegracao.STATUS_NAO_APLICAVEL, resultado.statusCanhoto());
    }

    @Test
    void deveConciliarDuplicidadeSoapNoCanhotoComoCanhotoEnviado() throws Exception {
        ImageDownloader imageDownloader = mock(ImageDownloader.class);
        INFe portaNFe = mock(INFe.class);

        when(imageDownloader.baixarImagemDaUrl(
                "https://assinada.exemplo/canhoto.jpg",
                "35260612345678000123570010000012341000012345"
        )).thenReturn(new byte[] { 1, 2, 3 });
        when(portaNFe.enviarDigitalizacaoCanhoto(any(Canhoto.class)))
                .thenThrow(criarErroSoap("Canhoto duplicado"));

        VedacitIntegrationService service = new VedacitIntegrationService(
                imageDownloader,
                mock(RodogarciaClient.class)
        ) {
            @Override
            protected INFe criarPortaNFe() {
                return portaNFe;
            }
        };
        ReflectionTestUtils.setField(service, "envioCanhotoHabilitado", true);

        ResultadoIntegracao resultado = service.processarOcorrencia(
                criarOcorrencia(),
                criarComprovante(),
                true,
                false
        );

        assertEquals(ResultadoIntegracao.STATUS_ENVIADO, resultado.status());
        assertEquals(ResultadoIntegracao.STATUS_SUCESSO, resultado.statusDados());
        assertEquals(ResultadoIntegracao.STATUS_SUCESSO, resultado.statusCanhoto());
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

    private SOAPFaultException criarErroSoap(String mensagem) throws Exception {
        return new SOAPFaultException(SOAPFactory.newInstance().createFault(
                mensagem,
                new QName("http://schemas.xmlsoap.org/soap/envelope/", "Client")
        ));
    }
}
