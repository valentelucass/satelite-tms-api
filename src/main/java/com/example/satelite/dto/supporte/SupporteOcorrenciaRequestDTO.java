package com.example.satelite.dto.supporte;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SupporteOcorrenciaRequestDTO(
        @JsonProperty("dataHoraEnvio") String dataHoraEnvio,
        @JsonProperty("cnpjTransportadora") String cnpjTransportadora,
        @JsonProperty("cnpjPagador") String cnpjPagador,
        SupporteNfDTO nf,
        SupporteCteDTO cte,
        @JsonProperty("ocorrencia") SupporteEventoDTO evento
) {
}
