package com.example.satelite.dto.rodogarcia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ComprovanteEslPagingDTO(
        @JsonProperty("next_id")
        Long nextId,
        @JsonProperty("last_id")
        Long lastId,
        Integer per,
        Integer size,
        Integer total) {
}
