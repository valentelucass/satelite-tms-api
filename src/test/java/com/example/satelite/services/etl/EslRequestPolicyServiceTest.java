package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class EslRequestPolicyServiceTest {

    private final EslRequestPolicyService service = new EslRequestPolicyService(
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
}
