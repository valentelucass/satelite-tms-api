package com.example.satelite.dto.ppg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PpgLoginResponseDTO(
    String id,
    Integer ttl,
    String created,
    Integer userId
) {
}
