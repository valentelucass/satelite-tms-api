package com.example.satelite.dto.selia;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeliaAddEventsRequestDTO(
        @JsonProperty("invoice_key")
        String invoiceKey,
        @JsonProperty("invoice_series")
        String invoiceSeries,
        @JsonProperty("invoice_number")
        String invoiceNumber,
        @JsonProperty("order_number")
        String orderNumber,
        @JsonProperty("volume_number")
        String volumeNumber,
        List<SeliaTrackingEventDTO> events
) {
}
