package com.example.satelite.utils;

import java.util.UUID;

/** Escopo técnico propagado explicitamente às chamadas executadas em outra thread. */
public final class ContextoCicloIntegracao implements AutoCloseable {
    private static final ThreadLocal<UUID> ATUAL = new ThreadLocal<>();
    private final UUID anterior;
    private ContextoCicloIntegracao(UUID id) { anterior = ATUAL.get(); ATUAL.set(id); }
    public static ContextoCicloIntegracao abrir(UUID id) { return new ContextoCicloIntegracao(id); }
    public static UUID atual() { return ATUAL.get(); }
    @Override public void close() { if (anterior == null) ATUAL.remove(); else ATUAL.set(anterior); }
}
