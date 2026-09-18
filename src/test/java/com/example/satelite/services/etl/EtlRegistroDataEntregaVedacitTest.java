package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocument;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocumentSource;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.vedacit.VedacitDataEntregaService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;
import com.example.satelite.utils.ImageDownloader;
import com.example.satelite.vedacit.nfe.Canhoto;
import com.example.satelite.vedacit.nfe.INFe;

class EtlRegistroDataEntregaVedacitTest {

    private static final String NFE = "35260912345678000123550010000012341000012345";
    private static final String CTE = "35260912345678000123570010000012341000012345";
    private static final OffsetDateTime ENTREGA_REAL = OffsetDateTime.parse("2026-09-18T11:08:30-03:00");

    private final LogIntegracaoRepository repositorio = mock(LogIntegracaoRepository.class);
    private final RodogarciaClient rodogarcia = mock(RodogarciaClient.class);
    private final EslRequestPolicyService politica = mock(EslRequestPolicyService.class);
    private final VedacitDataEntregaService dataEntrega = mock(VedacitDataEntregaService.class);
    private final VedacitSftpDocumentSource sftp = mock(VedacitSftpDocumentSource.class);
    private final SftpDocumentoLockService locks = mock(SftpDocumentoLockService.class);
    private final INFe portaSoap = mock(INFe.class);
    private final LogIntegracaoModel registro = registroPendente();

    private EtlRegistroService service;

    @BeforeEach
    void preparar() throws Exception {
        EtlEstadoIntegracaoService estado = new EtlEstadoIntegracaoService(repositorio);
        VedacitIntegrationService vedacit = new VedacitIntegrationService(
                mock(ImageDownloader.class), rodogarcia, politica, sftp
        ) {
            @Override
            protected INFe criarPortaNFe() {
                return portaSoap;
            }
        };
        ReflectionTestUtils.setField(vedacit, "envioCanhotoHabilitado", true);
        ReflectionTestUtils.setField(vedacit, "envioOcorrenciaHabilitado", false);
        ReflectionTestUtils.setField(vedacit, "envioXmlCteHabilitado", false);
        ReflectionTestUtils.setField(vedacit, "canhotoExclusivamenteSftp", true);

        service = new EtlRegistroService(
                rodogarcia,
                politica,
                mock(EtlResilienciaService.class),
                estado,
                mock(PpgIntegrationService.class),
                vedacit
        );
        ReflectionTestUtils.setField(service, "xmlDocumentoLockService", locks);
        ReflectionTestUtils.setField(service, "vedacitDataEntregaService", dataEntrega);

        when(repositorio.buscarDataHoraServidor()).thenReturn(LocalDateTime.of(2026, 9, 18, 12, 55));
        when(repositorio.findById(registro.getId())).thenReturn(Optional.of(registro));
        when(repositorio.save(any(LogIntegracaoModel.class))).thenAnswer(invocacao -> invocacao.getArgument(0));
        when(locks.executarComLock(anyString(), anyString(), anyString(), any())).thenAnswer(invocacao ->
                Optional.ofNullable(((Supplier<?>) invocacao.getArgument(3)).get()));
        when(sftp.buscarComprovante(CTE, NFE)).thenReturn(Optional.of(new VedacitSftpDocument(
                VedacitSftpDocument.Tipo.COMPROVANTE,
                "comprovantes/sintetico.jpg",
                CTE,
                NFE,
                10L,
                Instant.EPOCH,
                imagemJpeg()
        )));
        var aceite = new com.example.satelite.vedacit.nfe.RetornoOfboolean();
        aceite.setStatus(true);
        when(portaSoap.enviarDigitalizacaoCanhoto(any(Canhoto.class))).thenReturn(aceite);
    }

    @Test
    void enviaAoProxySoapAEntregaRealESeparaDataDeEnvioDaRegressaoConhecida() {
        when(dataEntrega.resolver(NFE, CTE)).thenReturn(
                VedacitDataEntregaService.Resolucao.encontrada(ENTREGA_REAL, 313075894L)
        );

        ResultadoRegistro resultado = service.reprocessarCanhotoVedacitPorCte(registro, sftp);

        ArgumentCaptor<Canhoto> canhoto = ArgumentCaptor.forClass(Canhoto.class);
        verify(portaSoap).enviarDigitalizacaoCanhoto(canhoto.capture());
        assertEquals(ResultadoRegistro.ENVIADO, resultado);
        assertEquals("18/09/2026 11:08:30", canhoto.getValue().getDataEntregaNota().getValue());
        assertNotEquals("18/09/2026 12:51:39", canhoto.getValue().getDataEntregaNota().getValue());
        assertNotEquals(canhoto.getValue().getDataEntregaNota().getValue(),
                canhoto.getValue().getDataEnvioCanhoto().getValue());
        assertEquals(313075894L, registro.getOccurrenceId());
    }

    @Test
    void converteUtcParaBrtNoCanhotoCapturadoPeloProxySoap() {
        when(dataEntrega.resolver(NFE, CTE)).thenReturn(VedacitDataEntregaService.Resolucao.encontrada(
                OffsetDateTime.parse("2026-09-19T01:08:30Z"), 901L
        ));

        service.reprocessarCanhotoVedacitPorCte(registro, sftp);

        ArgumentCaptor<Canhoto> canhoto = ArgumentCaptor.forClass(Canhoto.class);
        verify(portaSoap).enviarDigitalizacaoCanhoto(canhoto.capture());
        assertEquals("18/09/2026 22:08:30", canhoto.getValue().getDataEntregaNota().getValue());
    }

    @Test
    void retencaoAntesDoSoapNaoCriaTimeoutAmbiguoNemTentativa() {
        when(dataEntrega.resolver(NFE, CTE)).thenReturn(
                VedacitDataEntregaService.Resolucao.ambigua("DATA_ENTREGA_OCORRENCIAS_CONFLITANTES")
        );

        ResultadoRegistro resultado = service.reprocessarCanhotoVedacitPorCte(registro, sftp);

        assertEquals(ResultadoRegistro.PENDENTE_FOTO, resultado);
        assertEquals("PENDENTE_FOTO", registro.getStatusCanhoto());
        assertEquals("PENDENTE_ENVIO", registro.getCanhotoClassificacaoOperacional());
        assertEquals("DATA_ENTREGA_OCORRENCIAS_CONFLITANTES", registro.getMensagemErroCanhoto());
        assertEquals(0, registro.getTentativasCanhoto());
        verify(portaSoap, never()).enviarDigitalizacaoCanhoto(any());
        verify(sftp, never()).buscarComprovante(anyString(), anyString());
    }

    @Test
    void sucessoAnteriorOuTimeoutProtegidoNaoConsultaDataNemChamaSoap() {
        when(repositorio.existsConfirmacaoDuravelComprovante(NFE, CTE)).thenReturn(true);

        assertEquals(ResultadoRegistro.JA_PROCESSADO, service.reprocessarCanhotoVedacitPorCte(registro, sftp));
        verify(dataEntrega, never()).resolver(anyString(), anyString());
        verify(portaSoap, never()).enviarDigitalizacaoCanhoto(any());

        when(repositorio.existsConfirmacaoDuravelComprovante(NFE, CTE)).thenReturn(false);
        registro.setCanhotoClassificacaoOperacional("TIMEOUT_AMBIGUO");
        assertEquals(ResultadoRegistro.IGNORADO, service.reprocessarCanhotoVedacitPorCte(registro, sftp));
        verify(dataEntrega, never()).resolver(anyString(), anyString());
    }

    @Test
    void pendenciaGenericaVedacitUsaMesmoCaminhoProtegidoAntesDoSoap() {
        when(repositorio.findBySistemaDestinoAndStatusCanhotoOrderByDataProcessamentoAscIdAsc(
                "VEDACIT", "PENDENTE_FOTO"
        )).thenReturn(List.of(registro));
        when(dataEntrega.resolver(NFE, CTE)).thenReturn(
                VedacitDataEntregaService.Resolucao.ausente("DATA_ENTREGA_OCORRENCIA_NAO_ENCONTRADA")
        );
        ProcessadorDestino processadorGenerico = mock(ProcessadorDestino.class);

        ResultadoPagina resultado = service.processarPendenciasDestino(
                "VEDACIT", "Bearer token-que-nao-deve-ser-usado", processadorGenerico
        );

        assertEquals(1, resultado.pendentesFoto());
        assertEquals("DATA_ENTREGA_OCORRENCIA_NAO_ENCONTRADA", registro.getMensagemErroCanhoto());
        verify(processadorGenerico, never()).processar(any(), any(), any());
        verify(portaSoap, never()).enviarDigitalizacaoCanhoto(any());
        verify(sftp, never()).buscarComprovante(anyString(), anyString());
    }

    @Test
    void releituraArquivadaDuranteConsultaDaDataInterrompeAntesDoSoap() {
        LogIntegracaoModel arquivado = registroPendente();
        arquivado.setArquivado(true);
        when(repositorio.findById(registro.getId()))
                .thenReturn(Optional.of(registro))
                .thenReturn(Optional.of(arquivado));
        when(dataEntrega.resolver(NFE, CTE)).thenReturn(
                VedacitDataEntregaService.Resolucao.encontrada(ENTREGA_REAL, 313075894L)
        );

        ResultadoRegistro resultado = service.reprocessarCanhotoVedacitPorCte(registro, sftp);

        assertEquals(ResultadoRegistro.IGNORADO, resultado);
        verify(portaSoap, never()).enviarDigitalizacaoCanhoto(any());
        verify(sftp, never()).buscarComprovante(anyString(), anyString());
    }

    private LogIntegracaoModel registroPendente() {
        return LogIntegracaoModel.builder()
                .id(71230L)
                .sistemaDestino("VEDACIT")
                .sftpCliente("VEDACIT")
                .chaveNfe(NFE)
                .chaveCte(CTE)
                .status("PARCIAL")
                .statusDados("SUCESSO")
                .statusCanhoto("PENDENTE_FOTO")
                .canhotoClassificacaoOperacional("PENDENTE_ENVIO")
                .dataProcessamentoDados(LocalDateTime.of(2026, 9, 18, 12, 51, 39))
                .tentativasCanhoto(0)
                .dataProcessamento(LocalDateTime.of(2026, 9, 18, 12, 51, 39))
                .build();
    }

    private byte[] imagemJpeg() throws Exception {
        BufferedImage imagem = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D desenho = imagem.createGraphics();
        desenho.setColor(Color.WHITE);
        desenho.fillRect(0, 0, 80, 40);
        desenho.dispose();
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        ImageIO.write(imagem, "jpg", saida);
        return saida.toByteArray();
    }
}
