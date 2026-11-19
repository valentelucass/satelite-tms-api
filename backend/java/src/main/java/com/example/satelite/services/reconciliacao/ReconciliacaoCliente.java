package com.example.satelite.services.reconciliacao;

import java.time.Clock;
import java.time.LocalDateTime;

/** Cada adaptador executa uma passagem limitada e persiste seu próprio checkpoint. */
public interface ReconciliacaoCliente {
    String cliente();
    void validarEstrutura();
    void processarPassagem(Clock relogio, LocalDateTime fimJanela, LocalDateTime proximaRevisao);
}
