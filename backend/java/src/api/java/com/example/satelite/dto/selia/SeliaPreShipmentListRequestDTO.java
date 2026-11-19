package com.example.satelite.dto.selia;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeliaPreShipmentListRequestDTO(
        @JsonProperty("intelipost_pre_shipment_list")
        Long intelipostPreShipmentList,
        @JsonProperty("shipment_order_array")
        List<SeliaPreShipmentOrderDTO> shipmentOrders
) {
}
