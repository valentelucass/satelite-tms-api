package com.example.satelite.services.etl;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record ExecucaoEtlRequest(
        ModoExecucao modo,
        LocalDate dataInicial,
        LocalDate dataFinal,
        Set<String> destinos,
        boolean buscarCursorInicial,
        boolean persistirCursor,
        boolean processarPendencias,
        int maxPaginas
) {

    public ExecucaoEtlRequest {
        Objects.requireNonNull(modo, "Modo de execucao deve ser informado");
        destinos = normalizarDestinos(destinos);

        if (maxPaginas <= 0) {
            throw new IllegalArgumentException("Quantidade maxima de paginas deve ser maior que zero");
        }

        if (modo == ModoExecucao.RETROATIVO) {
            Objects.requireNonNull(dataInicial, "Data inicial retroativa deve ser informada");
            Objects.requireNonNull(dataFinal, "Data final retroativa deve ser informada");
            if (dataFinal.isBefore(dataInicial)) {
                throw new IllegalArgumentException("Data final retroativa nao pode ser anterior a data inicial");
            }
        }
    }

    public static ExecucaoEtlRequest incremental(int maxPaginas) {
        return new ExecucaoEtlRequest(
                ModoExecucao.INCREMENTAL,
                null,
                null,
                Set.of(),
                true,
                true,
                true,
                maxPaginas
        );
    }

    public static ExecucaoEtlRequest retroativo(
            LocalDate dataInicial,
            LocalDate dataFinal,
            String destino,
            int maxPaginas
    ) {
        return new ExecucaoEtlRequest(
                ModoExecucao.RETROATIVO,
                dataInicial,
                dataFinal,
                Set.of(normalizarDestino(destino)),
                false,
                false,
                false,
                maxPaginas
        );
    }

    public boolean retroativo() {
        return modo == ModoExecucao.RETROATIVO;
    }

    public boolean destinoSelecionado(String destino) {
        if (destinos.isEmpty() || destinos.contains("TODOS")) {
            return true;
        }

        return destinos.contains(normalizarDestino(destino));
    }

    private static Set<String> normalizarDestinos(Set<String> destinos) {
        if (destinos == null || destinos.isEmpty()) {
            return Set.of();
        }

        return destinos.stream()
                .map(ExecucaoEtlRequest::normalizarDestino)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizarDestino(String destino) {
        if (destino == null || destino.isBlank()) {
            return "TODOS";
        }

        String destinoNormalizado = destino.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PPG", "SELIA", "VEDACIT", "TODOS").contains(destinoNormalizado)) {
            throw new IllegalArgumentException("Destino retroativo invalido: " + destino);
        }

        return destinoNormalizado;
    }

    public enum ModoExecucao {
        INCREMENTAL,
        RETROATIVO
    }
}
