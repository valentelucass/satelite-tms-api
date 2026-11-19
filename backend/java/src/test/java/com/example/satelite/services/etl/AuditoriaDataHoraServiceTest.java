package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import com.example.satelite.repositories.LogIntegracaoRepository;

class AuditoriaDataHoraServiceTest {
    private final LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
    private final AuditoriaDataHoraService service = new AuditoriaDataHoraService(repository);

    @Test
    void serverClockTakesPrecedence() {
        var reference = LocalDateTime.of(2026, 1, 1, 0, 0);
        when(repository.buscarDataHoraServidor()).thenReturn(reference);
        assertEquals(reference, service.agora());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fallsBackToOperationalTimezoneWhenServerClockIsUnavailable(boolean failure) {
        if (failure) when(repository.buscarDataHoraServidor()).thenThrow(new DataAccessResourceFailureException("unit failure"));
        var zone = ZoneId.of("America/Sao_Paulo");
        var before = LocalDateTime.now(zone);
        var actual = service.agora();
        var after = LocalDateTime.now(zone);
        assertFalse(actual.isBefore(before));
        assertFalse(actual.isAfter(after));
    }
}
