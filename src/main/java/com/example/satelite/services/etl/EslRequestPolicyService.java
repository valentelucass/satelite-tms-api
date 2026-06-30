package com.example.satelite.services.etl;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import feign.FeignException;
import feign.RetryableException;

@Service
public class EslRequestPolicyService {

    private static final Logger log = LoggerFactory.getLogger(EslRequestPolicyService.class);
    public static final int STATUS_SEM_RESPOSTA_HTTP = -1;
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
        } catch (RetryableException e) {
            if (e.status() == 429) {
                throw tratarTooManyRequests(operacao, e);
            }

            if (CODIGOS_HTTP_TRANSITORIOS_ESL.contains(e.status())) {
                throw tratarFalhaTransitoria(operacao, e);
            }

            throw tratarFalhaTransporte(operacao, e);
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
        } catch (EslRequestTransientException e) {
            throw e;
        } catch (RuntimeException e) {
            if (falhaTransporteOuTimeout(e)) {
                throw tratarFalhaTransporte(operacao, e);
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

    private EslRequestTransientException tratarFalhaTransporte(String operacao, RuntimeException e) {
        String operacaoNormalizada = normalizarOperacao(operacao);
        log.warn(
                "Timeout ou falha de transporte na comunicação com a ESL em {}. Falha tratada como transitória. causa={}",
                operacaoNormalizada,
                obterResumoCausa(e)
        );
        return new EslRequestTransientException(
                operacaoNormalizada,
                STATUS_SEM_RESPOSTA_HTTP,
                "Timeout na comunicacao com a ESL em " + operacaoNormalizada,
                e
        );
    }

    private boolean falhaTransporteOuTimeout(Throwable erro) {
        Throwable atual = erro;
        while (atual != null) {
            if (atual instanceof SocketTimeoutException
                    || atual instanceof HttpTimeoutException
                    || atual instanceof TimeoutException
                    || atual instanceof InterruptedIOException
                    || atual instanceof ConnectException
                    || atual instanceof UnknownHostException
                    || atual instanceof SocketException) {
                return true;
            }

            atual = atual.getCause();
        }

        String mensagem = erro.getMessage();
        if (mensagem == null) {
            return false;
        }

        String normalizada = mensagem.toLowerCase();
        return normalizada.contains("read timed out")
                || normalizada.contains("connect timed out")
                || normalizada.contains("connection timed out")
                || normalizada.contains("timeout");
    }

    private String obterResumoCausa(Throwable erro) {
        Throwable causaRaiz = erro;
        while (causaRaiz.getCause() != null) {
            causaRaiz = causaRaiz.getCause();
        }

        String nomeCausa = causaRaiz.getClass().getSimpleName();
        String mensagem = causaRaiz.getMessage();
        if (mensagem == null || mensagem.isBlank()) {
            return nomeCausa;
        }

        return nomeCausa + ": " + mensagem;
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
            this(operacao, status, "Falha transitoria da ESL em " + operacao + " (HTTP " + status + ")", cause);
        }

        public EslRequestTransientException(String operacao, int status, String message, Throwable cause) {
            super(message, cause);
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
