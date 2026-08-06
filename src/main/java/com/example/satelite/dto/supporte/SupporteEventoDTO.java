package com.example.satelite.dto.supporte;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupporteEventoDTO(
        @JsonProperty("dataHoraEvento") String dataHoraEvento,
        int codigo,
        String descricao,
        String complemento,
        @JsonProperty("nomeRecebedor") String nomeRecebedor,
        @JsonProperty("docRecebedor") String docRecebedor,
        String latitude,
        String longitude,
        @JsonProperty("dataHoraAgendamento") String dataHoraAgendamento,
        @JsonProperty("imagemComprovante") String imagemComprovante,
        @JsonProperty("linkComprovante") String linkComprovante
) {
}
