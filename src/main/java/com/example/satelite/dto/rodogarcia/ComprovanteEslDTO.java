package com.example.satelite.dto.rodogarcia;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ComprovanteEslDTO(
        List<ComprovanteEslItemDTO> data,
        ComprovanteEslPagingDTO paging) {
}
