package com.example.satelite.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.config.BeanPostProcessor;

class DashboardApiOnlyConfigTest {

    @Test
    void deveSubstituirRunnerPorNoOpNoModoDashboard() throws Exception {
        AtomicInteger execucoes = new AtomicInteger();
        CommandLineRunner runnerExterno = argumentos -> execucoes.incrementAndGet();
        BeanPostProcessor bloqueador = new DashboardApiOnlyConfig().bloquearRunnersDeIntegracao();

        ((CommandLineRunner) bloqueador.postProcessAfterInitialization(runnerExterno, "runnerExterno")).run();

        assertEquals(0, execucoes.get());
    }
}
