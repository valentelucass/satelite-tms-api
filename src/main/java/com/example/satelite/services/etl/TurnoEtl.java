package com.example.satelite.services.etl;

import java.util.function.LongSupplier;

/** Cede entre documentos; nunca cancela um envio para cumprir a duração do turno. */
public final class TurnoEtl {
    private final int limite;
    private final long duracaoNanos;
    private final Runnable proximaFila;
    private final LongSupplier relogio;
    private int avaliados;
    private long inicio;
    private ResultadoPagina progressoXml = ResultadoPagina.vazio();
    private java.util.function.Consumer<ResultadoPagina> observador = resultado -> { };

    void observarProgresso(java.util.function.Consumer<ResultadoPagina> observador) {
        this.observador = java.util.Objects.requireNonNull(observador);
    }

    void documentoAvaliado(ResultadoRegistro resultado) {
        progressoXml = progressoXml.com(resultado);
        observador.accept(progressoXml);
        documentoAvaliado();
    }

    public TurnoEtl(int limite, long duracaoMs, Runnable proximaFila) {
        this(limite, duracaoMs, proximaFila, System::nanoTime);
    }

    TurnoEtl(int limite, long duracaoMs, Runnable proximaFila, LongSupplier relogio) {
        if (limite < 1 || duracaoMs < 1) throw new IllegalArgumentException("Turno inválido");
        this.limite = limite;
        this.duracaoNanos = Math.multiplyExact(duracaoMs, 1_000_000L);
        this.proximaFila = java.util.Objects.requireNonNull(proximaFila);
        this.relogio = relogio;
        inicio = relogio.getAsLong();
    }

    public void documentoAvaliado() {
        avaliados++;
        verificarTempo();
    }

    public void verificarTempo() {
        if (avaliados >= limite || relogio.getAsLong() - inicio >= duracaoNanos) {
            proximaFila.run();
            avaliados = 0;
            inicio = relogio.getAsLong();
        }
    }
}
