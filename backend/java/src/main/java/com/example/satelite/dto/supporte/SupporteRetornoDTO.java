package com.example.satelite.dto.supporte;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SupporteRetornoDTO(
        Integer codigo,
        String descricao,
        Integer numeroNFe,
        String pedido,
        String dataHora,
        String protocolo,
        Boolean comprovanteRecebido
) {
}
