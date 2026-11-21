package com.example.satelite.dto.selia;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeliaPreShipmentResponseVolumeDTO(
        @JsonProperty("shipment_order_volume_number")
        String shipmentOrderVolumeNumber
) {
}
