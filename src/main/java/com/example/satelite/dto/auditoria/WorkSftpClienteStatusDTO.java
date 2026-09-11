package com.example.satelite.dto.auditoria;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Visão agregada e sem dados fiscais do último ciclo SFTP de cada cliente. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
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
        LocalDateTime proximaExecucaoEstimada,
        Boolean xmlHabilitado, Integer xmlAvaliados, Integer xmlEnviados, Integer xmlJaProcessados, Integer xmlPendentes, Integer xmlErros, Integer errosComprovante, String motivoFalha
) {
    public WorkSftpClienteStatusDTO(String cliente, LocalDateTime inicioUltimoCiclo, LocalDateTime fimUltimoCiclo,
            String conexao, String statusCiclo, int arquivosValidos, int arquivosRejeitados, int selecionados,
            int enviados, int pendentes, long saldo, long bloqueios, long timeoutsAmbiguos, long duracaoMs,
            LocalDateTime proximaExecucaoEstimada) {
        this(cliente, inicioUltimoCiclo, fimUltimoCiclo, conexao, statusCiclo, arquivosValidos, arquivosRejeitados,
                selecionados, enviados, pendentes, saldo, bloqueios, timeoutsAmbiguos, duracaoMs,
                proximaExecucaoEstimada, null, null, null, null, null, null, null, null);
    }

    /** Esta projeção pertence exclusivamente ao worker de comprovantes SFTP. */
    @JsonProperty("origemComprovantes")
    public String origemComprovantes() {
        return "SFTP";
    }
}
