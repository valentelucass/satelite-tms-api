package com.example.satelite.dto.rodogarcia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ComprovanteEslFreightDTO(
        Long id,
        @JsonProperty("cte_number")
        Long cteNumber,
        @JsonProperty("cte_key")
        String cteKey,
        @JsonProperty("draft_number")
        Long draftNumber) {
}
