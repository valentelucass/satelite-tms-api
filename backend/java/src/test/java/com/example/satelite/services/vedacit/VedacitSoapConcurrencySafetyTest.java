package com.example.satelite.services.vedacit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.services.etl.EslRequestPolicyService;
import com.example.satelite.services.etl.ClassificacaoOperacionalCanhotoVedacit;
import com.example.satelite.utils.ImageDownloader;

class VedacitSoapConcurrencySafetyTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelamentoNaoLiberaSoapEnquantoChamadaAindaExecuta(boolean workerClientes) throws Exception {
        var service = new VedacitIntegrationService(mock(ImageDownloader.class), mock(RodogarciaClient.class), mock(EslRequestPolicyService.class));
        ReflectionTestUtils.setField(service, "workerClientes", workerClientes);
        ReflectionTestUtils.setField(service, "soapInvocationTimeoutMs", 300);
        var iniciou = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        var terminou = new CountDownLatch(1);
        var externo = Executors.newSingleThreadExecutor();
        try {
            Future<Exception> primeira = externo.submit(() -> assertThrows(IOException.class, () -> executar(service, () -> {
                iniciou.countDown();
                try {
                    while (true) { try { liberar.await(); break; } catch (InterruptedException ignorada) { /* Simula transporte que ignora cancelamento. */ } }
                    return "primeira";
                } finally { terminou.countDown(); }
            })));
            assertTrue(iniciou.await(5, TimeUnit.SECONDS));
            assertTrue(primeira.get(5, TimeUnit.SECONDS).getMessage().contains("Timeout total"));
            assertEquals(1, terminou.getCount());
            var naoIniciada = assertThrows(IOException.class, () -> executar(service, () -> fail("Não pode haver SOAP sobreposto")));
            assertTrue(naoIniciada.getMessage().startsWith("SOAP_ANTERIOR_EM_ANDAMENTO"));
            assertEquals(ClassificacaoOperacionalCanhotoVedacit.PENDENTE_TECNICO,
                    ClassificacaoOperacionalCanhotoVedacit.paraErro(naoIniciada.getMessage()));
        } finally {
            liberar.countDown(); assertTrue(terminou.await(5, TimeUnit.SECONDS)); externo.shutdownNow();
        }
        Semaphore gate = (Semaphore) ReflectionTestUtils.getField(VedacitIntegrationService.class, "SOAP_EM_CURSO");
        long prazo=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (gate.availablePermits()==0 && System.nanoTime()<prazo) Thread.yield();
        assertEquals("proxima", executar(service, () -> "proxima"));
    }
    @Test void workerAguardaOutraRotinaNormalAntesDeUsarOTransporte() throws Exception {
        var service = new VedacitIntegrationService(mock(ImageDownloader.class), mock(RodogarciaClient.class),
                mock(EslRequestPolicyService.class));
        ReflectionTestUtils.setField(service, "workerClientes", true);
        ReflectionTestUtils.setField(service, "soapInvocationTimeoutMs", 10_000);
        var primeiraIniciada = new CountDownLatch(1);
        var segundaPronta = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        var externo = Executors.newFixedThreadPool(2);
        try {
            var primeira = externo.submit(() -> service.executarSoapComPrazo(() -> {
                primeiraIniciada.countDown();
                liberar.await();
                return "sftp";
            }, "sftp"));
            assertTrue(primeiraIniciada.await(5, TimeUnit.SECONDS));
            var segunda = externo.submit(() -> {
                segundaPronta.countDown();
                return service.executarSoapComPrazo(() -> "revisao", "revisao");
            });
            assertTrue(segundaPronta.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> segunda.get(100, TimeUnit.MILLISECONDS));
            liberar.countDown();
            assertEquals("sftp", primeira.get(5, TimeUnit.SECONDS));
            assertEquals("revisao", segunda.get(5, TimeUnit.SECONDS));
        } finally {
            liberar.countDown();
            externo.shutdownNow();
        }
    }

    @Test void esperaDoWorkerTemPrazoESaiAoSerInterrompidaSemIniciarTransporte() throws Exception {
        var service = new VedacitIntegrationService(mock(ImageDownloader.class), mock(RodogarciaClient.class),
                mock(EslRequestPolicyService.class));
        ReflectionTestUtils.setField(service, "workerClientes", true);
        ReflectionTestUtils.setField(service, "soapInvocationTimeoutMs", 20);
        Semaphore fila = (Semaphore) ReflectionTestUtils.getField(service, "filaSoapWorker");
        fila.acquire();
        try {
            IOException esgotada = assertThrows(IOException.class, () ->
                    service.executarSoapComPrazo(() -> fail("Não deve iniciar o transporte"), "fila"));
            assertTrue(esgotada.getMessage().startsWith("SOAP_ANTERIOR_EM_ANDAMENTO"));
            Thread.currentThread().interrupt();
            IOException interrompida = assertThrows(IOException.class, () ->
                    service.executarSoapComPrazo(() -> fail("Não deve iniciar o transporte"), "fila"));
            assertTrue(interrompida.getMessage().contains("interrompida"));
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted();
            fila.release();
        }
    }

    private Object executar(VedacitIntegrationService service, Callable<?> chamada) throws Exception {
        var m = VedacitIntegrationService.class.getDeclaredMethod("executarSoapComPrazo", Callable.class, String.class);
        m.setAccessible(true);
        try { return m.invoke(service, chamada, "teste"); }
        catch (InvocationTargetException e) { throw (Exception)e.getCause(); }
    }
}
