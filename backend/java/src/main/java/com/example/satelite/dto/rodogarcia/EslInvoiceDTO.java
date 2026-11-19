package com.example.satelite.dto.rodogarcia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EslInvoiceDTO(
    Long id,
    String key,
    String series,
    String number
) {
}
