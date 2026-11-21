package com.example.satelite.dto.selia;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeliaPreShipmentVolumeDTO(
        @JsonProperty("shipment_order_volume_number")
        String shipmentOrderVolumeNumber,
        @JsonProperty("shipment_order_volume_invoice_array")
        List<SeliaPreShipmentInvoiceDTO> invoices
) {
}
