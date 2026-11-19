package com.example.satelite.dto.rodogarcia;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ComprovanteEslItemDTO(
        Long id,
        @JsonProperty("image_url")
        String imageUrl,
        @JsonProperty("created_at")
        OffsetDateTime createdAt,
        @JsonProperty("updated_at")
        OffsetDateTime updatedAt,
        ComprovanteEslFreightDTO freight) {
}
