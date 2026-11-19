package com.example.satelite.dto.etl;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record QuarentenaErroManualDTO(
        Long id,
        String destino,
        String chaveNfe,
        Long numeroNf,
        Integer tentativas,
        String erroLimpo,
        LocalDateTime dataUltimaTentativa
) {
}
