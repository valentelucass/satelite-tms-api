package com.example.satelite.dto.ppg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PpgOcorrenciaResponseDTO(
    String documento,
    String ocorrenciaentregaId,
    String statusbaixa,
    Integer statuscomprovante,
    String motivorecusa
) {
}
