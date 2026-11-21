package com.example.satelite.dto.auditoria;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IndicadoresOperacionaisDTO(int versao, LocalDate dataInicial, LocalDate dataFinal,
        List<Etapa> etapas, List<Dia> evolucao, List<Atividade> atividade,
        LocalDateTime historicoDesde, String escopoTransmissoes) {
    public record Etapa(String sistemaDestino, String etapa, long sucessosPeriodo,
            long aguardandoProcessamento, long novaTentativa, long aguardandoOrigem,
            long aguardandoConfirmacao, long intervencao, long semConfirmacaoDatada, long confirmadosSemDataConfiavel) {}
    public record Dia(LocalDate data, String etapa, long sucessos) {}
    public record Atividade(LocalDate data, String etapa, long avaliacoes, long enviosIniciados,
            long reenviosIniciados, long consultasOrigem, long retencoes, long confirmacoesAvaliadas) {}
}
