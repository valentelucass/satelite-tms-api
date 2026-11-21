package com.example.satelite.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import com.example.satelite.repositories.ReconciliacaoLocalRepository;
import com.example.satelite.services.etl.LogFileRetentionService;
import com.example.satelite.services.etl.OrquestradorEtlScheduler;
import com.example.satelite.services.etl.RepescagemNoturnaVedacitScheduler;
import com.example.satelite.services.etl.SftpDocumentoLockService;
import com.example.satelite.services.etl.WorkSftpClientesRunner;
import com.example.satelite.services.reconciliacao.ReconciliacaoClientesScheduler;

class ClientesWorkerConfigTest {

    private Map<String, Object> perfil() {
        return new HashMap<>(Map.of(
                "APP_CLIENTES_WORKER_ENABLED", "true",
                "spring.main.web-application-type", "none",
                "APP_RECONCILIACAO_ENABLED", "true",
                "APP_SCHEDULER_ENABLED", "false",
                "APP_ETL_REPESCAGEM_ENABLED", "false",
                "VEDACIT_SFTP_RECEIPT_ONLY", "true"));
    }

    private AnnotationConfigApplicationContext contexto(Map<String, Object> propriedades) {
        var contexto = new AnnotationConfigApplicationContext();
        var ambiente = new MockEnvironment();
        ambiente.getPropertySources().addFirst(new MapPropertySource("worker", propriedades));
        contexto.setEnvironment(ambiente);
        contexto.register(ClientesWorkerConfig.class);
        return contexto;
    }

    @Test
    void mantemReconciliacaoERetencaoSemHabilitarIntegracaoGeralOuRunnerAvulso() {
        var propriedades = perfil();
        propriedades.put("APP_LOG_RETENTION_ENABLED", "true");
        propriedades.put("APP_LOG_RETENTION_INITIAL_DELAY_MS", "3600000");
        propriedades.put("RECONCILIACAO_INITIAL_DELAY_MS", "3600000");
        propriedades.put("RECONCILIACAO_CLIENTES", "SELIA");
        try (var contexto = contexto(propriedades)) {
            contexto.registerBean(ReconciliacaoLocalRepository.class, () -> mock(ReconciliacaoLocalRepository.class));
            contexto.registerBean(SftpDocumentoLockService.class, () -> mock(SftpDocumentoLockService.class));
            contexto.register(ReconciliacaoClientesConfig.class, ReconciliacaoClientesScheduler.class,
                    SchedulerConfig.class, LogFileRetentionService.class, OrquestradorEtlScheduler.class,
                    RepescagemNoturnaVedacitScheduler.class, WorkSftpClientesRunner.class, DashboardApiOnlyConfig.class);
            contexto.refresh();

            assertNotNull(contexto.getBean(ReconciliacaoClientesScheduler.class));
            assertNotNull(contexto.getBean(LogFileRetentionService.class));
            assertTrue(contexto.getBeansOfType(OrquestradorEtlScheduler.class).isEmpty());
            assertTrue(contexto.getBeansOfType(WorkSftpClientesRunner.class).isEmpty());
            assertTrue(contexto.getBeansOfType(RepescagemNoturnaVedacitScheduler.class).isEmpty());
            assertTrue(contexto.getBeansOfType(DashboardApiOnlyConfig.class).isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "spring.main.web-application-type,servlet", "spring.main.web-application-type,",
            "APP_RECONCILIACAO_ENABLED,false", "VEDACIT_SFTP_RECEIPT_ONLY,false",
            "APP_SCHEDULER_ENABLED,true", "APP_SCHEDULER_ENABLED,", "APP_ETL_REPESCAGEM_ENABLED,",
            "APP_DASHBOARD_API_ONLY,true", "work.sftp-clientes.enabled,true", "APP_CICLO_UNICO,true",
            "ciclo_unico,true", "retroactive.enabled,true", "RETROACTIVE_ENABLED,true", "APP_NIGHTLY_RETRY_ENABLED,true",
            "vedacit.nightly-retry.run-on-start,true", "vedacit.sftp-inventory.enabled,true"
    })
    void configuracaoIncompativelFalhaAntesDeInstanciarServicos(String chave, String valor) {
        var propriedades = perfil();
        if (valor == null) {
            propriedades.remove(chave);
        } else {
            propriedades.put(chave, valor);
        }
        var iniciou = new AtomicBoolean();
        try (var contexto = contexto(propriedades)) {
            contexto.registerBean("servicoOperacional", Object.class, () -> {
                iniciou.set(true);
                return new Object();
            });
            var erro = assertThrows(IllegalStateException.class, contexto::refresh);
            assertTrue(erro.getMessage().contains(chave), erro::getMessage);
            assertFalse(iniciou.get());
        }
    }

    @Test
    void perfilLegadoNaoExigeConfiguracaoDoWorkerUnificado() {
        try (var contexto = contexto(Map.of("APP_DASHBOARD_API_ONLY", "true"))) {
            contexto.refresh();
            assertTrue(contexto.getBeansOfType(ClientesWorkerConfig.class).isEmpty());
        }
    }
}
