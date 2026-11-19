package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.*;
import com.example.satelite.dto.etl.QuarentenaHistoricoDTO;

class QuarentenaServiceTest {
    private final LogIntegracaoRepository logs = mock(LogIntegracaoRepository.class);
    private final IntegracaoAuditoriaQueryRepository query = mock(IntegracaoAuditoriaQueryRepository.class);
    private final QuarentenaEventoRepository events = mock(QuarentenaEventoRepository.class);
    private final QuarentenaService service = new QuarentenaService(logs, query, events);

    @Test
    void manualErrorKeepsLatestAttemptAndHighestCounterWithoutHtmlOrStackTrace() {
        var latest = LocalDateTime.of(2026, 9, 9, 10, 0);
        var row = LogIntegracaoModel.builder().id(1L).sistemaDestino("VEDACIT")
                .chaveNfe("1".repeat(25) + "000000123" + "2".repeat(10))
                .tentativasDados(2).tentativasCanhoto(4)
                .dataProcessamento(latest.minusHours(2)).dataProcessamentoDados(latest.minusHours(1)).dataProcessamentoCanhoto(latest)
                .mensagemErroDados("java.lang.IllegalStateException: <b>Recusado</b>\n at example.method(Test.java:1)\nSuppressed: internal")
                .build();
        var dto = service.mapearErroManual(row);
        assertEquals(123L, dto.numeroNf());
        assertEquals(4, dto.tentativas());
        assertEquals(latest, dto.dataUltimaTentativa());
        assertEquals("Recusado", dto.erroLimpo());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void legacyIncompleteAuditCanStillBeDisplayed(String key) {
        var dto = service.mapearErroManual(LogIntegracaoModel.builder().chaveNfe(key).build());
        assertNull(dto.numeroNf());
        assertNull(dto.dataUltimaTentativa());
        assertEquals(0, dto.tentativas());
        assertEquals("Motivo indisponivel", dto.erroLimpo());
    }

    @Test
    void receiptErrorHasPriorityOverLegacyErrorWhenDataErrorIsEmpty() {
        var row = LogIntegracaoModel.builder().mensagemErroDados(" ").mensagemErroCanhoto("Caused by: java.io.IOException: indisponivel")
                .erro("erro antigo").build();
        assertEquals("indisponivel", service.erroLimpo(row));
        row.setMensagemErroCanhoto(null);
        assertEquals("erro antigo", service.erroLimpo(row));
        assertEquals("Motivo indisponivel", service.erroLimpo(null));
    }

    @Test
    void historyUsesInclusiveCalendarDaysAndExclusiveSqlEnd() {
        var start = LocalDate.of(2026, 9, 1);
        var end = LocalDate.of(2026, 9, 9);
        var page = PageRequest.of(0, 20);
        var row = event();
        when(events.buscarHistoricoRepescagens(any(), any(), any(), eq(page))).thenReturn(new PageImpl<>(List.of(row)));
        var result = service.buscarHistoricoRepescagens(page, List.of(" vedacit "), start, end);
        assertEquals(1, result.getTotalElements());
        verify(events).buscarHistoricoRepescagens(start.atStartOfDay(), end.plusDays(1).atStartOfDay(), List.of("VEDACIT"), page);
    }

    @Test
    void invalidPeriodCannotReachRepository() {
        var date = LocalDate.of(2026, 9, 9);
        assertThrows(ResponseStatusException.class, () -> service.buscarHistoricoRepescagens(PageRequest.of(0, 10), List.of("PPG"), date, date.minusDays(1)));
        assertThrows(ResponseStatusException.class, () -> service.exportarHistoricoRepescagens(List.of("PPG"), date, date.minusDays(1), ignored -> {}));
        verifyNoInteractions(events);
    }

    @Test
    void exportClosesRepositoryStreamEvenIfConsumerFails() {
        var closed = new AtomicBoolean();
        var row = event();
        when(events.exportarHistoricoRepescagens(any(), any(), any())).thenReturn(Stream.of(row).onClose(() -> closed.set(true)));
        assertThrows(IllegalStateException.class, () -> service.exportarHistoricoRepescagens(List.of("VEDACIT"), null, null,
                dto -> { throw new IllegalStateException("unit broken output"); }));
        assertTrue(closed.get());
    }

    @Test
    void exportMapsAuditedRows() {
        var row = event();
        when(events.exportarHistoricoRepescagens(any(), any(), any())).thenReturn(Stream.of(row));
        var results = new ArrayList<QuarentenaHistoricoDTO>();
        service.exportarHistoricoRepescagens(List.of("VEDACIT"), null, null, results::add);
        assertEquals(1, results.size());
        verifyNoInteractions(logs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELIA", "SUPPORTE", "UNKNOWN"})
    void genericReprocessingCannotActivateUnapprovedDestinations(String destination) {
        assertThrows(IllegalArgumentException.class, () -> service.reprocessar(destination));
        verifyNoInteractions(logs);
    }

    private QuarentenaEventoRepository.HistoricoProjection event() {
        var row = mock(QuarentenaEventoRepository.HistoricoProjection.class);
        when(row.getId()).thenReturn(1L);
        when(row.getDestino()).thenReturn("VEDACIT");
        when(row.getMensagem()).thenReturn("<p>Recusado</p>");
        return row;
    }
}
