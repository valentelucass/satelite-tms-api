package com.example.satelite.services.etl;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import feign.FeignException;

@Service
public class EslRequestPolicyService {

    private static final Logger log = LoggerFactory.getLogger(EslRequestPolicyService.class);
    private static final Set<Integer> CODIGOS_HTTP_TRANSITORIOS_ESL = Set.of(429, 500, 502, 503, 504);

    private final Object requisicaoLock = new Object();
    private final ConcurrentMap<String, Instant> proximaExtracaoPorPeriodo = new ConcurrentHashMap<>();
    private final long intervaloMinimoRequisicoesMs;
    private final long cooldownTooManyRequestsMs;
    private final long limiteDiasPeriodoLivre;
    private final long limiteDiasPeriodoMedio;
    private final Duration cooldownPeriodoMedio;
    private final Duration cooldownPeriodoLongo;

    public EslRequestPolicyService(
            @Value("${ESL_MIN_INTERVAL_BETWEEN_REQUESTS_MS:2000}") long intervaloMinimoRequisicoesMs,
            @Value("${ESL_TOO_MANY_REQUESTS_BACKOFF_MS:30000}") long cooldownTooManyRequestsMs,
            @Value("${ESL_PERIOD_FREE_LIMIT_DAYS:30}") long limiteDiasPeriodoLivre,
            @Value("${ESL_PERIOD_MEDIUM_LIMIT_DAYS:183}") long limiteDiasPeriodoMedio,
            @Value("${ESL_PERIOD_MEDIUM_COOLDOWN_MS:3600000}") long cooldownPeriodoMedioMs,
            @Value("${ESL_PERIOD_LONG_COOLDOWN_MS:43200000}") long cooldownPeriodoLongoMs
    ) {
        this.intervaloMinimoRequisicoesMs = Math.max(0, intervaloMinimoRequisicoesMs);
        this.cooldownTooManyRequestsMs = Math.max(0, cooldownTooManyRequestsMs);
        this.limiteDiasPeriodoLivre = Math.max(0, limiteDiasPeriodoLivre);
        this.limiteDiasPeriodoMedio = Math.max(this.limiteDiasPeriodoLivre, limiteDiasPeriodoMedio);
        this.cooldownPeriodoMedio = Duration.ofMillis(Math.max(0, cooldownPeriodoMedioMs));
        this.cooldownPeriodoLongo = Duration.ofMillis(Math.max(0, cooldownPeriodoLongoMs));
    }

    public <T> T executar(String operacao, Supplier<T> chamada) {
        Objects.requireNonNull(chamada, "Chamada ESL deve ser informada");

        aguardarProximaRequisicao();

        try {
            return chamada.get();
        } catch (FeignException.TooManyRequests e) {
            throw tratarTooManyRequests(operacao, e);
        } catch (FeignException e) {
            if (e.status() == 429) {
                throw tratarTooManyRequests(operacao, e);
            }

            if (CODIGOS_HTTP_TRANSITORIOS_ESL.contains(e.status())) {
                throw tratarFalhaTransitoria(operacao, e);
            }

            throw e;
        }
    }

    public void aguardarProximaRequisicao() {
        if (intervaloMinimoRequisicoesMs <= 0) {
            return;
        }

        synchronized (requisicaoLock) {
            dormir(intervaloMinimoRequisicoesMs);
        }
    }

    public void pausarAposTooManyRequests() {
        if (cooldownTooManyRequestsMs <= 0) {
            return;
        }

        synchronized (requisicaoLock) {
            dormir(cooldownTooManyRequestsMs);
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

    private EslRequestTransientException tratarTooManyRequests(String operacao, FeignException e) {
        String operacaoNormalizada = normalizarOperacao(operacao);
        log.warn(
                "ESL retornou HTTP 429 em {}. Aplicando backoff antes de suspender a chamada. mensagem={}",
                operacaoNormalizada,
                e.getMessage()
        );
        pausarAposTooManyRequests();
        return new EslRequestTransientException(operacaoNormalizada, e.status(), e);
    }

    private EslRequestTransientException tratarFalhaTransitoria(String operacao, FeignException e) {
        String operacaoNormalizada = normalizarOperacao(operacao);
        log.warn(
                "ESL retornou HTTP {} em {}. Falha tratada como transitória; corpo da resposta omitido do log.",
                e.status(),
                operacaoNormalizada
        );
        return new EslRequestTransientException(operacaoNormalizada, e.status(), e);
    }

    private String normalizarOperacao(String operacao) {
        if (operacao == null || operacao.isBlank()) {
            return "chamada ESL";
        }

        return operacao.trim();
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

    public static class EslRequestTransientException extends RuntimeException {

        private final String operacao;
        private final int status;

        public EslRequestTransientException(String operacao, int status, Throwable cause) {
            super("Falha transitoria da ESL em " + operacao + " (HTTP " + status + ")", cause);
            this.operacao = operacao;
            this.status = status;
        }

        public String operacao() {
            return operacao;
        }

        public int status() {
            return status;
        }
    }
}
