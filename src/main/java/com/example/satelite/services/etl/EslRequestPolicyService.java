package com.example.satelite.services.etl;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EslRequestPolicyService {

    private final Object requisicaoLock = new Object();
    private final ConcurrentMap<String, Instant> proximaExtracaoPorPeriodo = new ConcurrentHashMap<>();
    private final long intervaloMinimoRequisicoesMs;
    private final long limiteDiasPeriodoLivre;
    private final long limiteDiasPeriodoMedio;
    private final Duration cooldownPeriodoMedio;
    private final Duration cooldownPeriodoLongo;

    private Instant proximaRequisicaoPermitida = Instant.EPOCH;

    public EslRequestPolicyService(
            @Value("${ESL_MIN_INTERVAL_BETWEEN_REQUESTS_MS:2000}") long intervaloMinimoRequisicoesMs,
            @Value("${ESL_PERIOD_FREE_LIMIT_DAYS:30}") long limiteDiasPeriodoLivre,
            @Value("${ESL_PERIOD_MEDIUM_LIMIT_DAYS:183}") long limiteDiasPeriodoMedio,
            @Value("${ESL_PERIOD_MEDIUM_COOLDOWN_MS:3600000}") long cooldownPeriodoMedioMs,
            @Value("${ESL_PERIOD_LONG_COOLDOWN_MS:43200000}") long cooldownPeriodoLongoMs
    ) {
        this.intervaloMinimoRequisicoesMs = Math.max(0, intervaloMinimoRequisicoesMs);
        this.limiteDiasPeriodoLivre = Math.max(0, limiteDiasPeriodoLivre);
        this.limiteDiasPeriodoMedio = Math.max(this.limiteDiasPeriodoLivre, limiteDiasPeriodoMedio);
        this.cooldownPeriodoMedio = Duration.ofMillis(Math.max(0, cooldownPeriodoMedioMs));
        this.cooldownPeriodoLongo = Duration.ofMillis(Math.max(0, cooldownPeriodoLongoMs));
    }

    public void aguardarProximaRequisicao() {
        if (intervaloMinimoRequisicoesMs <= 0) {
            return;
        }

        synchronized (requisicaoLock) {
            Instant agora = Instant.now();
            long esperaMs = Duration.between(agora, proximaRequisicaoPermitida).toMillis();
            if (esperaMs > 0) {
                dormir(esperaMs);
            }

            proximaRequisicaoPermitida = Instant.now().plusMillis(intervaloMinimoRequisicoesMs);
        }
    }

    public boolean podeExtrairPeriodo(String identificadorConsulta, LocalDate dataInicial, LocalDate dataFinal) {
        return tempoRestanteParaNovaExtracaoPeriodo(identificadorConsulta, dataInicial, dataFinal).isZero();
    }

    public Duration tempoRestanteParaNovaExtracaoPeriodo(
            String identificadorConsulta,
            LocalDate dataInicial,
            LocalDate dataFinal
    ) {
        calcularCooldownPeriodo(dataInicial, dataFinal);

        String chave = montarChavePeriodo(identificadorConsulta, dataInicial, dataFinal);
        Instant proximaPermitida = proximaExtracaoPorPeriodo.get(chave);
        if (proximaPermitida == null) {
            return Duration.ZERO;
        }

        Duration tempoRestante = Duration.between(Instant.now(), proximaPermitida);
        return tempoRestante.isNegative() ? Duration.ZERO : tempoRestante;
    }

    public void registrarExtracaoPeriodo(String identificadorConsulta, LocalDate dataInicial, LocalDate dataFinal) {
        Duration cooldown = calcularCooldownPeriodo(dataInicial, dataFinal);
        if (cooldown.isZero()) {
            return;
        }

        String chave = montarChavePeriodo(identificadorConsulta, dataInicial, dataFinal);
        proximaExtracaoPorPeriodo.put(chave, Instant.now().plus(cooldown));
    }

    public Duration calcularCooldownPeriodo(LocalDate dataInicial, LocalDate dataFinal) {
        validarPeriodo(dataInicial, dataFinal);

        long diasConsultados = ChronoUnit.DAYS.between(dataInicial, dataFinal) + 1;
        if (diasConsultados <= limiteDiasPeriodoLivre) {
            return Duration.ZERO;
        }

        if (diasConsultados <= limiteDiasPeriodoMedio) {
            return cooldownPeriodoMedio;
        }

        return cooldownPeriodoLongo;
    }

    private void validarPeriodo(LocalDate dataInicial, LocalDate dataFinal) {
        Objects.requireNonNull(dataInicial, "Data inicial da consulta ESL deve ser informada");
        Objects.requireNonNull(dataFinal, "Data final da consulta ESL deve ser informada");

        if (dataFinal.isBefore(dataInicial)) {
            throw new IllegalArgumentException("Data final da consulta ESL nao pode ser anterior a data inicial");
        }
    }

    private String montarChavePeriodo(String identificadorConsulta, LocalDate dataInicial, LocalDate dataFinal) {
        String identificador = identificadorConsulta == null || identificadorConsulta.isBlank()
                ? "default"
                : identificadorConsulta.trim();
        return identificador + ":" + dataInicial + ":" + dataFinal;
    }

    private void dormir(long esperaMs) {
        try {
            Thread.sleep(esperaMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Espera de limite da ESL interrompida", e);
        }
    }
}
