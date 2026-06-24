package com.example.satelite.dto.ppg;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PpgOcorrenciaRequestDTO(
    String documento,
    @JsonProperty("tipoocorrenciaId")
    Integer tipoOcorrenciaId,
    @JsonProperty("tipoentrega")
    String tipoEntrega,
    @JsonProperty("cnpjtransportadora")
    String cnpjTransportadora,
    @JsonProperty("entregadorId")
    Integer entregadorId,
    @JsonProperty("dtentrega")
    String dataEntrega,
    @JsonProperty("dtreentrega")
    String dataReentrega,
    @JsonProperty("dtsinistro")
    String dataSinistro,
    @JsonProperty("dtregistro")
    String dataRegistro,
    @JsonProperty("tipoentrada")
    String tipoEntrada,
    String latitude,
    String longitude,
    @JsonProperty("motivoocorrenciaId")
    Integer motivoOcorrenciaId,
    @JsonProperty("ocorrenciaentregafoto")
    List<PpgFotoDTO> ocorrenciaEntregaFoto
) {
}
