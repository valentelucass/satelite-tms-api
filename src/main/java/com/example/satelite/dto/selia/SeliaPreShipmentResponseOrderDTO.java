package com.example.satelite.dto.selia;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeliaPreShipmentResponseOrderDTO(
        @JsonProperty("order_number")
        String orderNumber,
        @JsonProperty("shipment_order_volume_array")
        List<SeliaPreShipmentResponseVolumeDTO> shipmentOrderVolumes
) {
}
