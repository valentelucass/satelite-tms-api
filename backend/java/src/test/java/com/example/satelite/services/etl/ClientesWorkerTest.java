package com.example.satelite.services.etl;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpClient;

class ClientesWorkerTest {
    private final CicloSftpClientes sftp = mock(CicloSftpClientes.class);
    private final VedacitSftpClient fonteXml = new VedacitSftpClient(false, "", 22, "", "",
            "/teste", "/teste/vedacit", "", 1024, 0);
    private final OrquestradorEtlService etl = mock(OrquestradorEtlService.class);
    private final OrquestradorEtlService.ResultadoCiclo resultado = mock(OrquestradorEtlService.ResultadoCiclo.class);

    @Test void agendaUmaVezPorRotinaComTrintaMinutosAposOFimMesmoComFalhas() {
        var executor = mock(ScheduledExecutorService.class);
        var reinicio = mock(Runnable.class);
        var worker = new ClientesWorker(sftp::executarCiclo, etl, 7, executor, reinicio);
        when(sftp.executarCiclo()).thenThrow(new IllegalStateException("falha do lote")).thenReturn(0);
        when(etl.executarFluxosComResultado(any())).thenReturn(resultado);
        when(resultado.codigoSaida()).thenReturn(1, 0);
        worker.iniciar();
        worker.iniciar();

        var tarefas = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, times(2)).scheduleWithFixedDelay(tarefas.capture(), eq(0L), eq(30L), eq(MINUTES));
        for (Runnable tarefa : tarefas.getAllValues()) {
            assertDoesNotThrow(tarefa::run);
            assertDoesNotThrow(tarefa::run);
        }
        verify(sftp, times(2)).executarCiclo();
        var pedido = ArgumentCaptor.forClass(ExecucaoEtlRequest.class);
        verify(etl, times(2)).executarFluxosComResultado(pedido.capture());
        assertEquals(Set.of("SELIA"), pedido.getValue().destinos());
        assertEquals(ExecucaoEtlRequest.ModoExecucao.INCREMENTAL, pedido.getValue().modo());
        assertEquals(7, pedido.getValue().maxPaginas());
        assertTrue(pedido.getValue().buscarCursorInicial());
        assertTrue(pedido.getValue().persistirCursor());
        assertFalse(pedido.getValue().processarPendencias());
        verifyNoMoreInteractions(etl);
        verifyNoInteractions(reinicio);
    }

    @Test void falhaFatalEmQualquerAgendaSolicitaUmReinicioDoProcesso() {
        var executor = mock(ScheduledExecutorService.class);
        var reinicio = mock(Runnable.class);
        var worker = new ClientesWorker(sftp::executarCiclo, etl, 7, executor, reinicio);
        when(sftp.executarCiclo()).thenThrow(new LinkageError("simulada"));
        when(etl.executarFluxosComResultado(any())).thenThrow(new LinkageError("simulada"));
        worker.iniciar();
        var tarefas = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, times(2)).scheduleWithFixedDelay(tarefas.capture(), eq(0L), eq(30L), eq(MINUTES));

        for (var tarefa : tarefas.getAllValues()) {
            assertThrows(LinkageError.class, tarefa::run);
            verify(reinicio, times(1)).run();
        }
    }

    @Test void cicloSftpDemoradoNaoBloqueiaSeliaERecebeInterrupcaoNoEncerramento() throws InterruptedException {
        var sftpIniciado = new CountDownLatch(1);
        var sftpInterrompido = new CountDownLatch(1);
        var seliaExecutada = new CountDownLatch(1);
        var bloqueioSftp = new CountDownLatch(1);
        when(sftp.executarCiclo()).thenAnswer(invocacao -> {
            sftpIniciado.countDown();
            try {
                bloqueioSftp.await();
                return 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sftpInterrompido.countDown();
                return 1;
            }
        });
        when(etl.executarFluxosComResultado(any())).thenAnswer(invocacao -> {
            if (sftpIniciado.await(5, SECONDS)) seliaExecutada.countDown();
            return resultado;
        });
        var worker = new ClientesWorker(sftp, fonteXml, etl, 3);
        try {
            worker.iniciar();
            assertTrue(seliaExecutada.await(8, SECONDS));
        } finally {
            worker.encerrar();
            bloqueioSftp.countDown();
        }
        assertEquals(0, sftpInterrompido.getCount());
    }

    @Test void perfilResidenteNaoInstanciaORunnerQueEncerraAJvm() {
        try (var contexto = new AnnotationConfigApplicationContext()) {
            contexto.setEnvironment(new MockEnvironment().withProperty("APP_CLIENTES_WORKER_ENABLED", "true"));
            contexto.registerBean(CicloSftpClientes.class, () -> sftp);
            contexto.registerBean(VedacitSftpClient.class, () -> fonteXml);
            contexto.registerBean(OrquestradorEtlService.class, () -> etl);
            contexto.register(WorkSftpClientesRunner.class, ClientesWorker.class);
            contexto.refresh();
            assertEquals(1, contexto.getBeansOfType(ClientesWorker.class).size());
            assertTrue(contexto.getBeansOfType(WorkSftpClientesRunner.class).isEmpty());
            verifyNoInteractions(sftp, etl);
        }
    }
}
