package com.example.satelite.dto.auditoria;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ResumoTabelaIntegracaoDTO(
        String entidadeTabela,
        long totalProcessado,
        long totalSucesso,
        long totalErro,
        long totalQuarentena
) {
}
