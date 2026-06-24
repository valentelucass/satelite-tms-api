package com.example.satelite.dto.rodogarcia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EslOccurrenceDefDTO(
    Long id,
    Integer code,
    String description
) {
}
