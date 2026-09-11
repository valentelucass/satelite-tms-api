package com.example.satelite.utils;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;

public final class FalhaIntegracaoSanitizada {
    private FalhaIntegracaoSanitizada() { }
    public static String resumir(Throwable erro) {
        var vistos = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable causa = erro;
        while (causa.getCause() != null && vistos.add(causa.getCause())) causa = causa.getCause();
        if ("MIGRACAO_V22_PENDENTE".equals(causa.getMessage())) return "MIGRACAO_V22_PENDENTE";
        if (causa instanceof SQLException sql) return "SQL_" + sql.getErrorCode();
        if (causa instanceof java.net.SocketTimeoutException || causa instanceof java.util.concurrent.TimeoutException)
            return "TIMEOUT_SEM_CONFIRMACAO";
        if (causa instanceof feign.FeignException http) return "HTTP_" + http.status();
        return causa.getClass().getSimpleName();
    }
}
