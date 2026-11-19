package com.example.satelite.dto.ppg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PpgLoginRequestDTO(
    String email,
    String password
) {
}
