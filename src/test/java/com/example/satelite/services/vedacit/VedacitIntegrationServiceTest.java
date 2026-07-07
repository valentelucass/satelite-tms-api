package com.example.satelite.services.vedacit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;

import javax.imageio.ImageIO;
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
import com.example.satelite.services.etl.EslRequestPolicyService;
import com.example.satelite.utils.ImageDownloader;
import com.example.satelite.vedacit.cte.ICTe;
import com.example.satelite.vedacit.nfe.Canhoto;
import com.example.satelite.vedacit.nfe.INFe;

import org.datacontract.schemas._2004._07.dominio_objetosdevalor_embarcador.Ocorrencia;
import org.tempuri.IOcorrencias;

class VedacitIntegrationServiceTest {

    @Test
    void deveCriarPortasSoapComWsdlLocalNoClasspath() throws Exception {
        VedacitIntegrationService service = new VedacitIntegrationService(
                mock(ImageDownloader.class),
                mock(RodogarciaClient.class),
                criarPoliticaEslExecutora()
        );
        ReflectionTestUtils.setField(service, "vedacitToken", "token-vedacit");
        ReflectionTestUtils.setField(service, "vedacitApiBaseUrl", "https://vedacit.multiembarcador.com.br/SGT.WebService");
        ReflectionTestUtils.setField(service, "soapConnectTimeoutMs", 30000);
        ReflectionTestUtils.setField(service, "soapReadTimeoutMs", 60000);

        assertNotNull(service.criarPortaOcorrencias());
        assertNotNull(service.criarPortaNFe());
        assertNotNull(service.criarPortaCte());
    }

    @Test
    void deveManterCanhotoPendenteQuandoDadosJaForamEnviadosEFotoNaoExiste() {
        VedacitIntegrationService service = new VedacitIntegrationService(
                mock(ImageDownloader.class),
                mock(RodogarciaClient.class),
                criarPoliticaEslExecutora()
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
                mock(RodogarciaClient.class),
                criarPoliticaEslExecutora()
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
        EslRequestPolicyService politicaEsl = criarPoliticaEslExecutora();
        ICTe portaCte = mock(ICTe.class);

        when(rodogarciaClient.buscarXmlCte(
                "Bearer token-cte",
                "35260612345678000123570010000012341000012345"
        )).thenReturn(new CteResponseDTO(List.of(new CteDataDTO(new CteItemDTO(1L, "autorizado", "<cte/>")))));
        when(portaCte.enviarArquivoXMLCTe(any(byte[].class)))
                .thenThrow(criarErroSoap("Carga já existe"));

        VedacitIntegrationService service = new VedacitIntegrationService(
                mock(ImageDownloader.class),
                rodogarciaClient,
                politicaEsl
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
        verify(politicaEsl).executar(contains("buscarXmlCte"), any());
    }

    @Test
    void deveRetornarErroDadosLimpoQuandoXmlCteHabilitadoSemChaveCte() {
        RodogarciaClient rodogarciaClient = mock(RodogarciaClient.class);
        EslRequestPolicyService politicaEsl = criarPoliticaEslExecutora();
        VedacitIntegrationService service = new VedacitIntegrationService(
                mock(ImageDownloader.class),
                rodogarciaClient,
                politicaEsl
        );
        ReflectionTestUtils.setField(service, "envioOcorrenciaHabilitado", false);
        ReflectionTestUtils.setField(service, "envioXmlCteHabilitado", true);
        ReflectionTestUtils.setField(service, "envioCanhotoHabilitado", false);

        ResultadoIntegracao resultado = service.processarOcorrencia(criarOcorrenciaSemCte(), null);

        assertEquals(ResultadoIntegracao.STATUS_ERRO_DESTINO, resultado.status());
        assertEquals(ResultadoIntegracao.STATUS_ERRO_DESTINO, resultado.statusDados());
        assertEquals("Chave CTe ausente para envio do XML CT-e", resultado.mensagemErroDados());
        verify(politicaEsl, never()).executar(contains("buscarXmlCte"), any());
        verifyNoInteractions(rodogarciaClient);
    }

    @Test
    void deveConciliarDuplicidadeSoapNoCanhotoComoCanhotoEnviado() throws Exception {
        ImageDownloader imageDownloader = mock(ImageDownloader.class);
        INFe portaNFe = mock(INFe.class);

        when(imageDownloader.baixarImagemDaUrl(
                "https://assinada.exemplo/canhoto.jpg",
                "35260612345678000123570010000012341000012345"
        )).thenReturn(criarImagemJpegTeste());
        when(portaNFe.enviarDigitalizacaoCanhoto(any(Canhoto.class)))
                .thenThrow(criarErroSoap("Canhoto duplicado"));

        VedacitIntegrationService service = new VedacitIntegrationService(
                imageDownloader,
                mock(RodogarciaClient.class),
                criarPoliticaEslExecutora()
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

    private EslOcorrenciaDTO criarOcorrenciaSemCte() {
        return new EslOcorrenciaDTO(
                10L,
                OffsetDateTime.parse("2026-06-17T10:30:00-03:00"),
                new EslInvoiceDTO(20L, "35260612345678000123550010000012341000012345", "1", "1234"),
                new EslFreightDTO(30L, null),
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

    private EslRequestPolicyService criarPoliticaEslExecutora() {
        EslRequestPolicyService service = mock(EslRequestPolicyService.class);
        when(service.executar(any(), any())).thenAnswer(invocation -> {
            Supplier<?> chamada = invocation.getArgument(1);
            return chamada.get();
        });
        return service;
    }

    private SOAPFaultException criarErroSoap(String mensagem) throws Exception {
        return new SOAPFaultException(SOAPFactory.newInstance().createFault(
                mensagem,
                new QName("http://schemas.xmlsoap.org/soap/envelope/", "Client")
        ));
    }

    private byte[] criarImagemJpegTeste() throws Exception {
        BufferedImage imagem = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = imagem.createGraphics();

        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, 1200, 800);
        g2d.setColor(Color.BLACK);
        g2d.fillRect(50, 50, 1100, 700);
        g2d.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(imagem, "jpg", baos);
        return baos.toByteArray();
    }
}
