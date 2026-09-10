package com.example.satelite.dto.auditoria;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Visão agregada e sem dados fiscais do último ciclo SFTP de cada cliente. */
public record WorkSftpClienteStatusDTO(
        String cliente,
        LocalDateTime inicioUltimoCiclo,
        LocalDateTime fimUltimoCiclo,
        String conexao,
        String statusCiclo,
        int arquivosValidos,
        int arquivosRejeitados,
        int selecionados,
        int enviados,
        int pendentes,
        long saldo,
        long bloqueios,
        long timeoutsAmbiguos,
        long duracaoMs,
        LocalDateTime proximaExecucaoEstimada
) {
    /** Esta projeção pertence exclusivamente ao worker de comprovantes SFTP. */
    @JsonProperty("origemComprovantes")
    public String origemComprovantes() {
        return "SFTP";
    }
}
