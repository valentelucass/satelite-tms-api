package com.example.satelite.dto.auditoria;

import java.math.BigDecimal;

public record MetricaConsolidadaDTO(
    String sistemaDestino,
    long totalRegistros,
    BigDecimal percentualXmlSucesso,
    BigDecimal percentualCanhotoSucesso
) {
    
}
