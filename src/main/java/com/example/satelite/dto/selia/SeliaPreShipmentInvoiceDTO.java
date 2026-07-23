package com.example.satelite.dto.selia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeliaPreShipmentInvoiceDTO(
        @JsonProperty("invoice_key")
        String invoiceKey
) {
}
