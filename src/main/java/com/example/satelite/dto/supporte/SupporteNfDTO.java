package com.example.satelite.dto.supporte;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SupporteNfDTO(
        @JsonProperty("serieNFe") int serieNfe,
        @JsonProperty("numeroNFe") int numeroNfe,
        @JsonProperty("chaveNFe") String chaveNfe,
        String pedido
) {
}
