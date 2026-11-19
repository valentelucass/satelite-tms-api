package com.example.satelite.dto.etl;

import java.time.LocalDateTime;

public record QuarentenaErroManualExportacaoDTO(
        Long id,
        String destino,
        String chaveNfe,
        Integer tentativasDados,
        Integer tentativasCanhoto,
        String mensagemErroDados,
        String mensagemErroCanhoto,
        String erro,
        LocalDateTime dataProcessamento,
        LocalDateTime dataProcessamentoDados,
        LocalDateTime dataProcessamentoCanhoto
) {
}
