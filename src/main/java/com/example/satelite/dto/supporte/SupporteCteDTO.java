package com.example.satelite.dto.supporte;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SupporteCteDTO(
        @JsonProperty("serieCTe") int serieCte,
        @JsonProperty("numeroCTe") int numeroCte,
        @JsonProperty("chaveCTe") String chaveCte
) {
}
