package com.example.satelite.dto.selia;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeliaPreShipmentListResponseDTO(
        @JsonProperty("intelipost_pre_shipment_list")
        Long intelipostPreShipmentList,
        @JsonProperty("logistics_provider_shipment_list")
        Long logisticsProviderShipmentList,
        @JsonProperty("shipment_list_creation_date")
        String shipmentListCreationDate,
        @JsonProperty("orders_array")
        List<SeliaPreShipmentResponseOrderDTO> orders,
        String status,
        List<SeliaPreShipmentMessageDTO> messages,
        String hash
) {
}
