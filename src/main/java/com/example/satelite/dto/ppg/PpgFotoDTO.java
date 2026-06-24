package com.example.satelite.dto.ppg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PpgFotoDTO(
    @JsonProperty("tipofoto")
    String tipoFoto,
    String foto,
    String mime,
    String extensao
) {
}
