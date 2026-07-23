package com.example.satelite.dto.selia;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeliaPreShipmentOrderDTO(
        @JsonProperty("order_number")
        String orderNumber,
        @JsonProperty("shipment_order_volume_array")
        List<SeliaPreShipmentVolumeDTO> shipmentOrderVolumes
) {
}
