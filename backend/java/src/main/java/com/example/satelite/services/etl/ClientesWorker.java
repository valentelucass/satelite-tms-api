package com.example.satelite.services.etl;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;

import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpClient;
import com.example.satelite.utils.FalhaIntegracaoSanitizada;

@Component
@ConditionalOnProperty(name = "APP_CLIENTES_WORKER_ENABLED", havingValue = "true")
class ClientesWorker {
    private static final Logger log = LoggerFactory.getLogger(ClientesWorker.class);
    private final IntSupplier cicloSftp;
    private final OrquestradorEtlService orquestrador;
    private final ExecucaoEtlRequest cicloSelia;
    private final ScheduledExecutorService executor;
    private final Runnable solicitarReinicio;
    private final AtomicBoolean encerrandoPorFalha = new AtomicBoolean();
    private boolean iniciado;

    @Autowired
    ClientesWorker(CicloSftpClientes cicloSftp, VedacitSftpClient fonteXml, OrquestradorEtlService orquestrador,
            @Value("${INTEGRATION_MAX_PAGES_PER_CYCLE:10}") int maxPaginas) {
        this(() -> fonteXml.comIndiceXmlDoCiclo(cicloSftp::executarCiclo), orquestrador, maxPaginas,
                Executors.newScheduledThreadPool(2, new CustomizableThreadFactory("satelite-clientes-")),
                // Os hooks de encerramento precisam conseguir aguardar as duas threads das agendas.
                new Thread(() -> System.exit(1), "satelite-clientes-saida")::start);
    }

    ClientesWorker(IntSupplier cicloSftp, OrquestradorEtlService orquestrador, int maxPaginas,
            ScheduledExecutorService executor, Runnable solicitarReinicio) {
        this.cicloSftp = cicloSftp;
        this.orquestrador = orquestrador;
        this.executor = executor;
        this.solicitarReinicio = solicitarReinicio;
        this.cicloSelia = new ExecucaoEtlRequest(ExecucaoEtlRequest.ModoExecucao.INCREMENTAL,
                null, null, Set.of("SELIA"), true, true, false, maxPaginas);
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void iniciar() {
        if (iniciado || executor.isShutdown()) return;
        iniciado = true;
        agendar("SFTP", cicloSftp);
        agendar("SELIA", () -> orquestrador.executarFluxosComResultado(cicloSelia).codigoSaida());
    }

    private void agendar(String nome, IntSupplier executar) {
        // Cada agenda conta o intervalo a partir do fim do próprio ciclo, inclusive quando houve falhas.
        executor.scheduleWithFixedDelay(() -> {
            try {
                int codigo = executar.getAsInt();
                log.info("[CLIENTES] rotina={} ciclo finalizado codigo={}", nome, codigo);
            } catch (RuntimeException e) {
                log.error("[CLIENTES] rotina={} ciclo interrompido motivo={}", nome, FalhaIntegracaoSanitizada.resumir(e));
            } catch (Error falhaFatal) {
                try {
                    log.error("[CLIENTES] rotina={} falha fatal tipo={}; encerrando o processo para reinício",
                            nome, falhaFatal.getClass().getName());
                } finally {
                    if (encerrandoPorFalha.compareAndSet(false, true)) solicitarReinicio.run();
                }
                throw falhaFatal;
            }
        }, 0, 30, TimeUnit.MINUTES);
    }

    @PreDestroy
    synchronized void encerrar() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("[CLIENTES] Um ciclo ainda estava em execução ao encerrar o worker");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
