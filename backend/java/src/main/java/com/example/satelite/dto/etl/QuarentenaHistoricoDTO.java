package com.example.satelite.dto.etl;

import java.time.LocalDateTime;

public record QuarentenaHistoricoDTO(
        Long id,
        String destino,
        String chaveNfe,
        Long numeroNf,
        String etapa,
        LocalDateTime entradaQuarentenaEm,
        LocalDateTime reprocessadoEm,
        String resultado,
        String motivo
) {
}
