package com.example.satelite.services.reconciliacao;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Um supervisor, uma passagem por cliente em voo e isolamento entre integrações. */
@Component
@ConditionalOnProperty(name = "APP_RECONCILIACAO_ENABLED", havingValue = "true")
public class ReconciliacaoClientesScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReconciliacaoClientesScheduler.class);
    private final List<Agenda> agendas;
    private final ExecutorService executor;
    private final Clock clock;

    @Autowired
    public ReconciliacaoClientesScheduler(List<ReconciliacaoCliente> adaptadores, Environment env) {
        this(adaptadores, env, Clock.systemUTC(), null);
    }

    ReconciliacaoClientesScheduler(List<ReconciliacaoCliente> adaptadores, Environment env,
            Clock clock, ExecutorService executor) {
        this.clock = clock;
        Map<String, ReconciliacaoCliente> disponiveis = new HashMap<>();
        for (ReconciliacaoCliente adaptador : adaptadores) {
            if (disponiveis.putIfAbsent(adaptador.cliente(), adaptador) != null)
                throw new IllegalArgumentException("ADAPTADOR_RECONCILIACAO_DUPLICADO: " + adaptador.cliente());
        }
        agendas = new ArrayList<>();
        var vistos = new HashSet<String>();
        for (String valor : env.getProperty("RECONCILIACAO_CLIENTES", "VEDACIT").split(",", -1)) {
            String cliente = valor.trim().toUpperCase(Locale.ROOT);
            if (!cliente.matches("[A-Z0-9_]+") || !vistos.add(cliente) || !disponiveis.containsKey(cliente))
                throw new IllegalArgumentException("CLIENTE_RECONCILIACAO_INVALIDO_OU_DUPLICADO: " + cliente);
            LocalTime inicio = LocalTime.parse(env.getProperty(cliente + "_RECONCILIACAO_START", "02:00"));
            LocalTime fim = LocalTime.parse(env.getProperty(cliente + "_RECONCILIACAO_END", "06:00"));
            if (!inicio.isBefore(fim)) throw new IllegalArgumentException("JANELA_RECONCILIACAO_INVALIDA: " + cliente);
            ZoneId zona = ZoneId.of(env.getProperty(cliente + "_RECONCILIACAO_TIME_ZONE",
                    env.getProperty("APP_TIME_ZONE", "America/Sao_Paulo")));
            agendas.add(new Agenda(disponiveis.get(cliente), inicio, fim, zona));
        }
        this.executor = executor != null ? executor : Executors.newFixedThreadPool(agendas.size(), tarefa -> {
            Thread thread = new Thread(tarefa, "reconciliacao-clientes");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    public void validarInicializacao() {
        for (Agenda agenda : agendas) {
            agenda.adaptador.validarEstrutura();
            log.info("[RECONCILIACAO-CLIENTES] cliente={} janela={}–{} fuso={}",
                    agenda.adaptador.cliente(), agenda.inicio, agenda.fim, agenda.zona);
        }
    }

    @Scheduled(fixedDelayString = "${RECONCILIACAO_POLL_MS:60000}",
            initialDelayString = "${RECONCILIACAO_INITIAL_DELAY_MS:1000}")
    public void verificarJanelas() {
        for (Agenda agenda : agendas) {
            Clock relogio = clock.withZone(agenda.zona);
            if (!agenda.dentro(LocalDateTime.now(relogio).toLocalTime())
                    || !agenda.executando.compareAndSet(false, true)) continue;
            try {
                executor.execute(() -> {
                    try {
                        LocalDateTime agora = LocalDateTime.now(relogio);
                        // A fila pode ter aguardado; não começar trabalho após o fim da janela.
                        if (!Thread.currentThread().isInterrupted() && agenda.dentro(agora.toLocalTime())) {
                            agenda.adaptador.processarPassagem(relogio, agora.toLocalDate().atTime(agenda.fim),
                                    agora.toLocalDate().plusDays(1).atTime(agenda.inicio));
                        }
                    } catch (RuntimeException ex) {
                        log.error("[RECONCILIACAO-CLIENTES] cliente={} passagem interrompida; demais clientes preservados",
                                agenda.adaptador.cliente());
                    } finally { agenda.executando.set(false); }
                });
            } catch (RuntimeException ex) {
                agenda.executando.set(false);
                log.error("[RECONCILIACAO-CLIENTES] cliente={} indisponível para nova passagem", agenda.adaptador.cliente());
            }
        }
    }

    @PreDestroy
    public void encerrar() {
        executor.shutdownNow();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private static final class Agenda {
        final ReconciliacaoCliente adaptador;
        final LocalTime inicio, fim;
        final ZoneId zona;
        final AtomicBoolean executando = new AtomicBoolean();
        Agenda(ReconciliacaoCliente adaptador, LocalTime inicio, LocalTime fim, ZoneId zona) {
            this.adaptador = adaptador; this.inicio = inicio; this.fim = fim; this.zona = zona;
        }
        boolean dentro(LocalTime hora) { return !hora.isBefore(inicio) && hora.isBefore(fim); }
    }
}
