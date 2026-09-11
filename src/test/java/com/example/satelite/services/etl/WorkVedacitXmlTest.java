package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ConfigurableApplicationContext;
import com.example.satelite.repositories.WorkSftpClientesAuditoriaRepository;
import com.example.satelite.services.origem.sftp.vedacit.*;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

class WorkVedacitXmlTest {
    @Test void etapaXmlConsultaSomenteEvento110ComCursorProprio() {
        var fluxo = mock(EtlFluxoDestinoService.class);
        var ppg = mock(PpgIntegrationService.class);
        var vedacit = mock(VedacitIntegrationService.class);
        var repescagem = mock(EtlRepescagemService.class);
        var service = new OrquestradorEtlService(ppg, vedacit, mock(EtlEstadoIntegracaoService.class), fluxo,
                mock(QuarentenaService.class), repescagem);
        ReflectionTestUtils.setField(service, "maxPaginasPorCiclo", 10);
        ReflectionTestUtils.setField(service, "tokenVedacitEsl", "teste");
        when(fluxo.executarFluxoDestino(eq("VEDACIT"), eq("VEDACIT_XML"), eq("teste"), any(), eq(110), eq(false), any()))
                .thenReturn(ResultadoDestino.vazio("VEDACIT"));
        assertNotNull(service.executarXmlVedacit());
        verify(fluxo).executarFluxoDestino(eq("VEDACIT"), eq("VEDACIT_XML"), eq("teste"),
                argThat(request -> request.persistirCursor() && request.buscarCursorInicial()), eq(110), eq(false), any());
        verifyNoMoreInteractions(fluxo);
        verifyNoInteractions(ppg, vedacit, repescagem);
    }

    @Test void workerIntercalaCanhotosDuranteEtapaXml() {
        var xml = mock(OrquestradorEtlService.class);
        var factory = mock(VedacitSftpClientFactory.class);
        var sftp = mock(VedacitSftpClient.class);
        var repescagem = mock(EtlRepescagemService.class);
        var env = new MockEnvironment().withProperty("VEDACIT_SFTP_RECEIPT_ONLY", "true")
                .withProperty("WORK_SFTP_CLIENTES_XML_ENABLED", "true").withProperty("SFTP_RODOGARCIA_ENABLED", "true");
        var inventario = new VedacitSftpInventory(List.of(), List.of());
        var ordemReal = new java.util.ArrayList<String>();
        when(xml.executarXmlVedacit(any(TurnoEtl.class))).thenAnswer(inv -> {
            ordemReal.add("XML-inicio");
            TurnoEtl turno = inv.getArgument(0);
            for (int i=0;i<20;i++) turno.documentoAvaliado();
            ordemReal.add("XML-fim");
            return ResultadoDestino.vazio("VEDACIT");
        });
        when(factory.criarClientesHabilitados()).thenReturn(List.of(new VedacitSftpClientFactory.ClienteSftp("VEDACIT", sftp, 10)));
        when(sftp.listarInventarioComprovantes()).thenReturn(inventario);
        when(repescagem.processarClienteSftpVedacit(eq("VEDACIT"), eq(inventario), eq(sftp), eq(10), eq(1000L), any(), eq(120000L))).thenAnswer(inv -> { ordemReal.add("POD"); return new EtlRepescagemService.ResultadoClienteSftpVedacit(new EtlRepescagemService.ResultadoInventarioSftpVedacit(0,0,0,0),
                        new EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit(0,0,0,0,0), 0); });
        var runner = new WorkSftpClientesRunner(factory, repescagem, env, mock(ConfigurableApplicationContext.class),
                mock(WorkSftpClientesAuditoriaRepository.class));
        ReflectionTestUtils.setField(runner, "orquestrador", xml);
        assertEquals(0, runner.executarCiclo());
        assertEquals(List.of("XML-inicio", "POD", "POD", "XML-fim", "POD"), ordemReal);
    }

    @Test void erroXmlTambemMarcaCicloComoFalhaMesmoSemErroDeComprovante() {
        var xml = mock(OrquestradorEtlService.class);
        var factory = mock(VedacitSftpClientFactory.class);
        var sftp = mock(VedacitSftpClient.class);
        var repescagem = mock(EtlRepescagemService.class);
        var auditoria = mock(WorkSftpClientesAuditoriaRepository.class);
        var env = new MockEnvironment().withProperty("VEDACIT_SFTP_RECEIPT_ONLY", "true")
                .withProperty("WORK_SFTP_CLIENTES_XML_ENABLED", "true").withProperty("SFTP_RODOGARCIA_ENABLED", "true");
        when(factory.criarClientesHabilitados()).thenReturn(List.of(new VedacitSftpClientFactory.ClienteSftp("VEDACIT", sftp, 10)));
        when(sftp.listarInventarioComprovantes()).thenReturn(new VedacitSftpInventory(List.of(), List.of()));
        when(xml.executarXmlVedacit(any(TurnoEtl.class))).thenReturn(ResultadoDestino.vazio("VEDACIT")
                .comRegistros(ResultadoPagina.vazio().com(ResultadoRegistro.RETIDO).com(ResultadoRegistro.PENDENTE_ORIGEM)));
        when(repescagem.processarClienteSftpVedacit(any(), any(), any(), anyInt(), anyLong(), any(), anyLong()))
                .thenReturn(new EtlRepescagemService.ResultadoClienteSftpVedacit(new EtlRepescagemService.ResultadoInventarioSftpVedacit(0,0,0,0),
                        new EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit(0,0,0,0,0), 0));
        var runner = new WorkSftpClientesRunner(factory, repescagem, env, mock(ConfigurableApplicationContext.class), auditoria);
        ReflectionTestUtils.setField(runner, "orquestrador", xml);
        assertEquals(1, runner.executarCiclo());
        verify(auditoria).registrarProgresso(any(), argThat(c -> c.xmlHabilitado() && c.xmlErros() == 1 && c.xmlPendentes() == 1
                && c.errosComprovante() == 0 && c.status().equals("FALHA") && c.motivoFalha().startsWith("XML_RETIDO")));
    }
}
