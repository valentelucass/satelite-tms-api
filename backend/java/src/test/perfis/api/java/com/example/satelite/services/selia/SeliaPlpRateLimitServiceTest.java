package com.example.satelite.services.selia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class SeliaPlpRateLimitServiceTest {

    @Test
    void deveLimitarChaveAutenticadaMesmoQuandoOIpMuda() {
        MutableClock clock = new MutableClock();
        SeliaPlpRateLimitService service = new SeliaPlpRateLimitService(true, 60_000, 2, 10, 100, clock);

        assertTrue(service.permitirChaveAutenticada("chave-valida", "10.0.0.1"));
        assertTrue(service.permitirChaveAutenticada("chave-valida", "10.0.0.2"));
        assertFalse(service.permitirChaveAutenticada("chave-valida", "10.0.0.3"));
    }

    @Test
    void deveLimitarIpParaPedidosSemChaveValida() {
        SeliaPlpRateLimitService service = new SeliaPlpRateLimitService(
                true,
                60_000,
                10,
                2,
                100,
                Clock.systemUTC()
        );

        assertTrue(service.permitirIp("10.0.0.1"));
        assertTrue(service.permitirIp("10.0.0.1"));
        assertFalse(service.permitirIp("10.0.0.1"));
    }

    @Test
    void deveLiberarIdentidadeAposExpiracaoDaJanela() {
        MutableClock clock = new MutableClock();
        SeliaPlpRateLimitService service = new SeliaPlpRateLimitService(true, 60_000, 1, 10, 100, clock);

        assertTrue(service.permitirChaveAutenticada("chave-valida", "10.0.0.1"));
        assertFalse(service.permitirChaveAutenticada("chave-valida", "10.0.0.1"));

        clock.avancarSegundos(60);

        assertTrue(service.permitirChaveAutenticada("chave-valida", "10.0.0.1"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-14T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void avancarSegundos(long segundos) {
            instant = instant.plusSeconds(segundos);
        }
    }
}
