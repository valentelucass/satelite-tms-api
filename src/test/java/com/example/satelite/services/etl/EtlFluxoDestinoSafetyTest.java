package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.*;
import com.example.satelite.models.ControleCursor;
import com.example.satelite.repositories.ControleCursorRepository;
import com.example.satelite.services.ResultadoIntegracao;

class EtlFluxoDestinoSafetyTest {
    private final RodogarciaClient esl = mock(RodogarciaClient.class);
    private final ControleCursorRepository cursors = mock(ControleCursorRepository.class);
    private final EtlRegistroService registros = mock(EtlRegistroService.class);
    private final EtlFluxoDestinoService fluxo = new EtlFluxoDestinoService(esl, cursors,
            new EslRequestPolicyService(0, 0, 0, 30, 183, 0, 0, mock(EslRequestTelemetryRecorder.class)), registros);

    @Test
    void neverPersistsCursorOlderThanRequested() {
        ReflectionTestUtils.setField(fluxo, "processarPendenciasEnabled", false);
        ReflectionTestUtils.setField(fluxo, "pausaPacingPaginacaoMs", 0L);
        when(cursors.findBySistemaDestino("PPG")).thenReturn(Optional.of(
                ControleCursor.builder().sistemaDestino("PPG").cursorNextId(100L).build()));
        var occurrence = event(80L, "2026-09-09T08:00:00-03:00", "2026-09-09T09:00:00-03:00");
        when(esl.buscarOcorrencias(eq("Bearer unit-token"), any(), any(), any(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(List.of(occurrence), new EslPagingDTO(90L, 1)))
                .thenReturn(new EslLoteResponseDTO(List.of(), null));
        when(registros.processarOcorrencia(anyString(), anyString(), any(), any(), any())).thenReturn(ResultadoRegistro.JA_PROCESSADO);
        fluxo.executarFluxoDestino("PPG", "unit-token", ExecucaoEtlRequest.incremental(1),
                (o, c, l) -> ResultadoIntegracao.enviado());
        verify(cursors, never()).save(any());
        verify(esl, times(1)).buscarOcorrencias(anyString(), any(), any(), any(), any());
    }

    @Test
    void seliaEventsAreProcessedByOccurrenceTimeEvenWhenCreatedInReverseOrder() {
        var early = event(2L, "2026-09-09T08:00:00-03:00", "2026-09-09T11:00:00-03:00");
        var late = event(1L, "2026-09-09T09:00:00-03:00", "2026-09-09T10:00:00-03:00");
        when(registros.obterOccurrenceId(any())).thenAnswer(i -> ((EslOcorrenciaDTO)i.getArgument(0)).id());
        when(registros.processarOcorrencia(anyString(), anyString(), any(), any(), any())).thenReturn(ResultadoRegistro.ENVIADO);
        fluxo.processarPagina("SELIA", "Bearer unit-token", 3L,
                new EslLoteResponseDTO(List.of(late, early), new EslPagingDTO(3L, 2)),
                ExecucaoEtlRequest.incremental(1), 0, (o,c,l) -> ResultadoIntegracao.enviado(), null);
        var order = inOrder(registros);
        order.verify(registros).processarOcorrencia(eq("SELIA"), anyString(), any(), eq(early), any());
        order.verify(registros).processarOcorrencia(eq("SELIA"), anyString(), any(), eq(late), any());
    }

    @Test
    void rejectsRegressingApiCursorEvenWhenCalculatedCursorWouldAdvance() {
        var current = new EtlFluxoDestinoService.AssinaturaPagina(List.of(11L), 1, 11L, 11L);
        assertTrue(fluxo.diagnosticarLoopPaginacao("PPG", 2, 10L, 9L, 11L, null, current).detectado());
    }

    @Test void xmlRetidoEmAuditoriaPermiteAvancarMasErroSemRetencaoPreservaCursor() {
        ReflectionTestUtils.setField(fluxo, "pausaPacingPaginacaoMs", 0L);
        var evento = event(101L, "2026-09-11T08:00:00-03:00", "2026-09-11T08:00:00-03:00");
        when(esl.buscarOcorrencias(anyString(), any(), any(), any(), eq(110)))
                .thenReturn(new EslLoteResponseDTO(List.of(evento), new EslPagingDTO(102L, 1)))
                .thenReturn(new EslLoteResponseDTO(List.of(), null));
        when(registros.processarEmissaoXmlVedacit(anyString(), any(), any())).thenReturn(ResultadoRegistro.RETIDO);
        var result = fluxo.executarFluxoDestino("VEDACIT", "VEDACIT_XML", "teste", ExecucaoEtlRequest.incremental(1),
                110, false, (o,c,l) -> ResultadoIntegracao.enviado(), new TurnoEtl(10, 120000, () -> {}));
        assertEquals(1, result.erros());
        verify(cursors).save(argThat(c -> c.getCursorNextId().equals(102L)));
        clearInvocations(cursors);
        when(esl.buscarOcorrencias(anyString(), any(), any(), any(), eq(110)))
                .thenReturn(new EslLoteResponseDTO(List.of(evento), new EslPagingDTO(102L, 1)));
        when(registros.processarEmissaoXmlVedacit(anyString(), any(), any())).thenReturn(ResultadoRegistro.ERRO);
        fluxo.executarFluxoDestino("VEDACIT", "VEDACIT_XML", "teste", ExecucaoEtlRequest.incremental(1),
                110, false, (o,c,l) -> ResultadoIntegracao.enviado(), null);
        verify(cursors, never()).save(any());
    }

    @Test void interrupcaoDuranteTurnoNaoPersistePaginaParcial() {
        var evento = event(101L, "2026-09-11T08:00:00-03:00", "2026-09-11T08:00:00-03:00");
        when(esl.buscarOcorrencias(anyString(), any(), any(), any(), eq(110)))
                .thenReturn(new EslLoteResponseDTO(List.of(evento, evento), new EslPagingDTO(102L, 2)));
        when(registros.processarEmissaoXmlVedacit(anyString(), any(), any())).thenReturn(ResultadoRegistro.ENVIADO);
        try {
            var resultado = fluxo.executarFluxoDestino("VEDACIT", "VEDACIT_XML", "teste", ExecucaoEtlRequest.incremental(1),
                    110, false, (o,c,l) -> ResultadoIntegracao.enviado(), new TurnoEtl(1, 120000, () -> Thread.currentThread().interrupt()));
            assertEquals(1, resultado.enviados());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(cursors, never()).save(any());
        } finally { Thread.interrupted(); }
    }

    @Test void maisDeDezPaginasMantemTurnosDeDezSemPerderCursorOuDocumentos() {
        ReflectionTestUtils.setField(fluxo, "pausaPacingPaginacaoMs", 0L);
        var paginas = new java.util.concurrent.atomic.AtomicInteger();
        var turnos = new java.util.concurrent.atomic.AtomicInteger();
        var documentos = new java.util.concurrent.atomic.AtomicInteger();
        when(esl.buscarOcorrencias(anyString(), any(), any(), any(), eq(110))).thenAnswer(i -> {
            int pagina = paginas.incrementAndGet();
            if (pagina > 12) return new EslLoteResponseDTO(List.of(), null);
            var lote = java.util.stream.LongStream.range(pagina * 20L, pagina * 20L + 20)
                    .mapToObj(id -> event(id, "2026-09-11T08:00:00-03:00", "2026-09-11T08:00:00-03:00")).toList();
            return new EslLoteResponseDTO(lote, new EslPagingDTO(pagina * 20L + 20, 20));
        });
        when(registros.processarEmissaoXmlVedacit(anyString(), any(), any())).thenAnswer(i -> {
            assertEquals(documentos.get() / 10, turnos.get()); documentos.incrementAndGet(); return ResultadoRegistro.ENVIADO;
        });
        var resultado = fluxo.executarFluxoDestino("VEDACIT", "VEDACIT_XML", "teste", ExecucaoEtlRequest.incremental(10),
                110, false, (o,c,l) -> ResultadoIntegracao.enviado(), new TurnoEtl(10, 120000, turnos::incrementAndGet));
        assertEquals(240, resultado.enviados()); assertEquals(24, turnos.get());
        assertEquals(12, resultado.paginasProcessadas());
        verify(cursors, times(12)).save(any());
        verify(cursors).save(argThat(c -> c.getCursorNextId() == 260L));
    }

    @Test
    void seliaRetroactivePageStillProcessesEligibleItemAfterOutOfWindowCreatedDate() {
        var outside = event(1L, "2026-09-01T08:00:00-03:00", "2026-09-10T10:00:00-03:00");
        var inside = event(2L, "2026-09-02T08:00:00-03:00", "2026-09-09T10:00:00-03:00");
        when(registros.obterOccurrenceId(any())).thenAnswer(i -> ((EslOcorrenciaDTO)i.getArgument(0)).id());
        when(registros.processarOcorrencia(anyString(), anyString(), any(), any(), any())).thenReturn(ResultadoRegistro.ENVIADO);
        var result = fluxo.processarPagina("SELIA", "Bearer unit-token", 3L,
                new EslLoteResponseDTO(List.of(outside, inside), new EslPagingDTO(3L, 2)),
                ExecucaoEtlRequest.retroativo(java.time.LocalDate.of(2026,9,1), java.time.LocalDate.of(2026,9,9), "SELIA", 1),
                0, (o,c,l) -> ResultadoIntegracao.enviado(), null);
        assertTrue(result.fimJanelaRetroativa());
        assertEquals(1, result.enviados());
        verify(registros, never()).processarOcorrencia(anyString(), anyString(), any(), eq(outside), any());
        verify(registros).processarOcorrencia(anyString(), anyString(), any(), eq(inside), any());
    }

    private EslOcorrenciaDTO event(long id, String occurrenceAt, String createdAt) {
        return new EslOcorrenciaDTO(id, OffsetDateTime.parse(occurrenceAt), OffsetDateTime.parse(createdAt),
                null, null, new EslOccurrenceDefDTO(1L, 1, "Entrega"));
    }
}
