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

    @Test void workerConcluiEtapaXmlAntesDeMaterializarEDrenarCanhotos() {
        var xml = mock(OrquestradorEtlService.class);
        var factory = mock(VedacitSftpClientFactory.class);
        var sftp = mock(VedacitSftpClient.class);
        var repescagem = mock(EtlRepescagemService.class);
        var env = new MockEnvironment().withProperty("VEDACIT_SFTP_RECEIPT_ONLY", "true")
                .withProperty("WORK_SFTP_CLIENTES_XML_ENABLED", "true").withProperty("SFTP_RODOGARCIA_ENABLED", "true");
        var inventario = new VedacitSftpInventory(List.of(), List.of());
        when(xml.executarXmlVedacit()).thenReturn(ResultadoDestino.vazio("VEDACIT"));
        when(factory.criarClientesHabilitados()).thenReturn(List.of(new VedacitSftpClientFactory.ClienteSftp("VEDACIT", sftp, 10)));
        when(sftp.listarInventarioComprovantes()).thenReturn(inventario);
        when(repescagem.processarClienteSftpVedacit("VEDACIT", inventario, sftp, 10, 1000L)).thenReturn(
                new EtlRepescagemService.ResultadoClienteSftpVedacit(new EtlRepescagemService.ResultadoInventarioSftpVedacit(0,0,0,0),
                        new EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit(0,0,0,0,0), 0));
        var runner = new WorkSftpClientesRunner(factory, repescagem, env, mock(ConfigurableApplicationContext.class),
                mock(WorkSftpClientesAuditoriaRepository.class));
        ReflectionTestUtils.setField(runner, "orquestrador", xml);
        assertEquals(0, runner.executarCiclo());
        var ordem = inOrder(xml, repescagem);
        ordem.verify(xml).executarXmlVedacit();
        ordem.verify(repescagem).processarClienteSftpVedacit("VEDACIT", inventario, sftp, 10, 1000L);
    }
}
