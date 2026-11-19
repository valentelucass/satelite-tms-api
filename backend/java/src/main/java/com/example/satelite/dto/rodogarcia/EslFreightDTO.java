package com.example.satelite.dto.rodogarcia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EslFreightDTO(
    Long id,
    @JsonProperty("cte_key") 
    String cteKey,
    @JsonProperty("order_number")
    String orderNumber,
    @JsonProperty("volume_number")
    String volumeNumber
) {

    public EslFreightDTO(Long id, String cteKey, String orderNumber) {
        this(id, cteKey, orderNumber, null);
    }

    public EslFreightDTO(Long id, String cteKey) {
        this(id, cteKey, null, null);
    }
}
