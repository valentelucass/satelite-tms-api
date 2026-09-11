package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TurnoEtlTest {
    @Test void alternaPorItensEPorTempoSemContarTempoDaOutraFila() {
        var relogio = new AtomicLong();
        var turnos = new AtomicInteger();
        var turno = new TurnoEtl(10, 120000, () -> {
            turnos.incrementAndGet(); relogio.addAndGet(300_000_000_000L);
        }, relogio::get);
        for (int i=0;i<9;i++) turno.documentoAvaliado();
        assertEquals(0, turnos.get());
        turno.documentoAvaliado();
        assertEquals(1, turnos.get());
        turno.verificarTempo();
        assertEquals(1, turnos.get());
        relogio.addAndGet(121_000_000_000L);
        turno.documentoAvaliado();
        assertEquals(2, turnos.get());
    }
}
