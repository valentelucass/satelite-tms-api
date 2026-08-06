package com.example.satelite.dto.supporte;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SupporteOcorrenciaResponseDTO(SupporteRetornoDTO retorno) {
}
