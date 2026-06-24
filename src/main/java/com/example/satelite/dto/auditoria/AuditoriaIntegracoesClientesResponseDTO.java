package com.example.satelite.dto.auditoria;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditoriaIntegracoesClientesResponseDTO(
        LocalDateTime geradoEm,
        List<MetricaConsolidadaDTO> metricasConsolidadas,
        PendenciasPaginadasDTO pendencias
) {

}
