package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;

class EslRequestPolicyServiceTest {

    private final EslRequestPolicyService service = new EslRequestPolicyService(
            0,
            0,
            30,
            183,
            Duration.ofHours(1).toMillis(),
            Duration.ofHours(12).toMillis()
    );

    @Test
    void deveLiberarPeriodoInferiorA31DiasSemCooldown() {
        Duration cooldown = service.calcularCooldownPeriodo(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 30)
        );

        assertEquals(Duration.ZERO, cooldown);
    }

    @Test
    void deveAplicarCooldownDeUmaHoraParaPeriodoEntre31DiasESeisMeses() {
        Duration cooldown = service.calcularCooldownPeriodo(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31)
        );

        assertEquals(Duration.ofHours(1), cooldown);
    }

    @Test
    void deveAplicarCooldownDeDozeHorasParaPeriodoAcimaDeSeisMeses() {
        Duration cooldown = service.calcularCooldownPeriodo(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 7, 3)
        );

        assertEquals(Duration.ofHours(12), cooldown);
    }

    @Test
    void deveRegistrarCooldownEmMemoriaParaPeriodoControlado() {
        LocalDate dataInicial = LocalDate.of(2026, 1, 1);
        LocalDate dataFinal = LocalDate.of(2026, 1, 31);

        assertTrue(service.podeExtrairPeriodo("PPG", dataInicial, dataFinal));

        service.registrarExtracaoPeriodo("PPG", dataInicial, dataFinal);

        assertFalse(service.podeExtrairPeriodo("PPG", dataInicial, dataFinal));
    }

    @Test
    void deveRejeitarPeriodoComDataFinalAnteriorAInicial() {
        assertThrows(IllegalArgumentException.class, () -> service.calcularCooldownPeriodo(
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 1, 31)
        ));
    }

    @Test
    void deveExecutarChamadaCentralizadaComFreioObrigatorio() {
        AtomicInteger chamadas = new AtomicInteger();

        String retorno = service.executar("buscarOcorrencias", () -> {
            chamadas.incrementAndGet();
            return "ok";
        });

        assertEquals("ok", retorno);
        assertEquals(1, chamadas.get());
    }

    @Test
    void deveConverterHttp429EmExcecaoTransitoriaDoExecutor() {
        EslRequestPolicyService.EslRequestTransientException erro = assertThrows(
                EslRequestPolicyService.EslRequestTransientException.class,
                () -> service.executar("buscarXmlCte", () -> {
                    throw criarErroTooManyRequestsEsl();
                })
        );

        assertEquals("buscarXmlCte", erro.operacao());
        assertEquals(429, erro.status());
    }

    private FeignException criarErroTooManyRequestsEsl() {
        Request request = Request.create(
                Request.HttpMethod.GET,
                "https://rodogarcia.eslcloud.com.br/api/customer/invoice_occurrences",
                Collections.emptyMap(),
                null,
                StandardCharsets.UTF_8,
                new RequestTemplate()
        );
        Response response = Response.builder()
                .status(429)
                .reason("Too Many Requests")
                .request(request)
                .body("rate limit", StandardCharsets.UTF_8)
                .build();

        return FeignException.errorStatus("RodogarciaClient#buscarXmlCte", response);
    }
}
