package com.example.satelite.dto.selia;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeliaPreShipmentMessageDTO(
        String type,
        String text,
        String key
) {
}
