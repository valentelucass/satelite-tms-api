package com.example.satelite.services.etl;

import java.util.Locale;

/** Contexto fechado de auditoria; nunca recebe URL, token ou chave fiscal. */
public record EslRequestContext(
        String origem,
        String destino,
        String rota,
        String template,
        boolean fallback,
        String cacheStatus
) {
    public static final String ORIGEM_ESL_CLOUD = "ESL_CLOUD";
    public static final String CACHE_NAO_APLICAVEL = "NAO_APLICAVEL";

    public EslRequestContext {
        origem = normalizar(origem, ORIGEM_ESL_CLOUD, 30);
        destino = normalizar(destino, "NAO_INFORMADO", 30);
        rota = normalizar(rota, "NAO_CLASSIFICADA", 50);
        template = normalizar(template, "PADRAO", 80);
        cacheStatus = normalizar(cacheStatus, CACHE_NAO_APLICAVEL, 20);
    }

    public static EslRequestContext criar(String destino, String rota) {
        return new EslRequestContext(ORIGEM_ESL_CLOUD, destino, rota, rota, false, CACHE_NAO_APLICAVEL);
    }

    private static String normalizar(String valor, String padrao, int tamanhoMaximo) {
        String normalizado = valor == null || valor.isBlank() ? padrao : valor.trim();
        if (normalizado.length() > tamanhoMaximo) {
            throw new IllegalArgumentException("Campo de telemetria ESL excede o limite permitido");
        }
        return normalizado.toUpperCase(Locale.ROOT);
    }
}
