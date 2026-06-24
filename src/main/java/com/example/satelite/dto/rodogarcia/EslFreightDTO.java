package com.example.satelite.dto.rodogarcia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EslFreightDTO(
    Long id,
    @JsonProperty("cte_key") 
    String cteKey
) {

}
