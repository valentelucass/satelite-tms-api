package com.example.satelite.dto.auditoria;

import java.time.LocalDateTime;

public record PendenciaDTO(
        Long id,
        String sistemaDestino,
        Long occurrenceId,
        Long freightId,
        String chaveNfe,
        Long numeroNf,
        String serieNf,
        String statusDados,
        String statusCanhoto,
        String mensagemErroDados,
        String mensagemErroCanhoto,
        LocalDateTime dataProcessamento,
        LocalDateTime dataProcessamentoDados,
        LocalDateTime dataProcessamentoCanhoto) {
}
