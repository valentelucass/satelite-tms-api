package com.example.satelite.dto.etl;

public record QuarentenaReprocessamentoResponseDTO(
        String destino,
        int quantidadeNotasReprocessadas,
        String mensagem
) {
}
