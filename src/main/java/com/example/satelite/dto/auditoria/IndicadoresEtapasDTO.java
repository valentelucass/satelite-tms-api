package com.example.satelite.dto.auditoria;

import java.time.LocalDate;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IndicadoresEtapasDTO(
        int versao, LocalDate dataInicial, LocalDate dataFinal,
        List<Etapa> etapas, List<Dia> evolucao
) {
    public record Etapa(String sistemaDestino, String etapa, long sucessosPeriodo,
            long falhasPeriodo, long pendentesAtuais, long bloqueadosAtuais, long semConfirmacaoDatada) {}

    public record Dia(LocalDate data, String etapa, long sucessos, long falhas) {}
}
