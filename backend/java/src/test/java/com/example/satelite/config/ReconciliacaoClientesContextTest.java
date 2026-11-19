package com.example.satelite.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import com.example.satelite.repositories.ReconciliacaoLocalRepository;
import com.example.satelite.services.etl.SftpDocumentoLockService;
import com.example.satelite.services.reconciliacao.ReconciliacaoCliente;
import com.example.satelite.services.reconciliacao.ReconciliacaoClientesScheduler;

class ReconciliacaoClientesContextTest {
    @Test void supervisorRegistraQuatroAdaptadoresSemDispararAoInicializar() {
        var local=mock(ReconciliacaoLocalRepository.class);
        var locks=mock(SftpDocumentoLockService.class);
        var vedacit=mock(ReconciliacaoCliente.class); when(vedacit.cliente()).thenReturn("VEDACIT");
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("supervisor-test",Map.of(
                    "APP_RECONCILIACAO_ENABLED","true", "APP_DASHBOARD_API_ONLY","false",
                    "RECONCILIACAO_CLIENTES","VEDACIT,PPG,SELIA,SUPPORTE",
                    "VEDACIT_SFTP_RECEIPT_ONLY","true", "RECONCILIACAO_INITIAL_DELAY_MS","3600000")));
            context.registerBean(ReconciliacaoLocalRepository.class,()->local);
            context.registerBean(SftpDocumentoLockService.class,()->locks);
            context.registerBean("vedacitAdapter",ReconciliacaoCliente.class,()->vedacit);
            context.register(ReconciliacaoClientesConfig.class,ReconciliacaoClientesScheduler.class);
            context.refresh();
            assertNotNull(context.getBean(ReconciliacaoClientesScheduler.class));
            assertEquals(Set.of("VEDACIT","PPG","SELIA","SUPPORTE"),context.getBeansOfType(ReconciliacaoCliente.class)
                    .values().stream().map(ReconciliacaoCliente::cliente).collect(Collectors.toSet()));
            verify(local,times(3)).validarEstrutura(); verify(vedacit).validarEstrutura();
            verify(vedacit,never()).processarPassagem(any(),any(),any()); verifyNoInteractions(locks);
        }
    }
    @Test void semFlagNaoCriaSupervisorNemAdaptadoresLocais() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("disabled-test",
                    Map.of("APP_RECONCILIACAO_ENABLED","false")));
            context.register(ReconciliacaoClientesConfig.class,ReconciliacaoClientesScheduler.class);
            context.refresh();
            assertTrue(context.getBeansOfType(ReconciliacaoClientesScheduler.class).isEmpty());
            assertTrue(context.getBeansOfType(ReconciliacaoCliente.class).isEmpty());
        }
    }
}
