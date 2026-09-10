package com.example.satelite.services.auditoria;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.*;

class IntegracaoAuditoriaServiceTest {
    private final LogIntegracaoRepository logs = mock(LogIntegracaoRepository.class);
    private final IntegracaoAuditoriaQueryRepository query = mock(IntegracaoAuditoriaQueryRepository.class);
    private final WorkSftpClientesAuditoriaRepository work = mock(WorkSftpClientesAuditoriaRepository.class);
    private final IntegracaoAuditoriaService service = new IntegracaoAuditoriaService(logs, query, work);

    @Test
    void paginatesAndNormalizesFiltersBeforeQuerying() {
        when(query.buscarPendencias(any(), anyInt(), anyInt())).thenReturn(new IntegracaoAuditoriaQueryRepository.PendenciasResultado(List.of(), 501));
        var params = new LinkedMultiValueMap<String,String>();
        params.add("destino", " vedacit "); params.add("destino", "VEDACIT");
        params.add("f.tabelaColuna.status", " SUCESSO "); params.add("f.tabelaColuna.status", "SUCESSO");
        params.add("f.tabelaColuna.", "ignored"); params.add("f.tabelaColuna.empty", " ");
        var response = service.consultarIntegracoesClientes(-1, 1000, "2026-09-01", "2026-09-09", params);
        assertEquals(2, response.pendencias().paginacao().totalPaginas());
        var filters = ArgumentCaptor.forClass(IntegracaoAuditoriaQueryRepository.Filtros.class);
        verify(query).buscarPendencias(filters.capture(), eq(0), eq(500));
        assertEquals(List.of("VEDACIT"), filters.getValue().destinos());
        assertEquals(List.of("SUCESSO"), filters.getValue().filtrosColuna().get("status"));
        assertEquals(1, filters.getValue().filtrosColuna().size());
        verify(logs).buscarMetricasIntegracoesClientes(LocalDate.of(2026,9,1).atStartOfDay(),
                LocalDate.of(2026,9,10).atStartOfDay(), List.of("VEDACIT"));
    }

    @ParameterizedTest
    @CsvSource({"PPG,XML/Dados,Canhoto", "SELIA,AddEvents,POD/Comprovante", "SUPPORTE,Ocorrência,Comprovante"})
    void metricLabelsReflectDestinationAndNullCountersAreZero(String destination, String dataLabel, String receiptLabel) {
        var metric = mock(LogIntegracaoRepository.MetricaIntegracaoClienteProjection.class);
        when(metric.getSistemaDestino()).thenReturn(destination);
        when(logs.buscarMetricasIntegracoesClientes(any(), any(), any())).thenReturn(List.of(metric));
        when(query.buscarPendencias(any(), anyInt(), anyInt())).thenReturn(new IntegracaoAuditoriaQueryRepository.PendenciasResultado(List.of(), 0));
        var result = service.consultarIntegracoesClientes(0, 0, null, null, null).metricasConsolidadas().get(0);
        assertEquals(dataLabel, result.rotuloDados());
        assertEquals(receiptLabel, result.rotuloComprovante());
        assertEquals(0, result.totalRegistros());
        assertEquals(BigDecimal.ZERO, result.percentualXmlSucesso());
    }

    @ParameterizedTest
    @CsvSource({"2026-09-09,2026-09-01", "not-date,2026-09-09", ",2026-09-09", "2026-09-09,"})
    void invalidPeriodNeverQueriesDatabase(String start, String end) {
        assertThrows(ResponseStatusException.class, () -> service.consultarIntegracoesClientes(0,10,start,end,null));
        assertThrows(ResponseStatusException.class, () -> service.consultarEvolucaoDiaria(start,end,null));
        verifyNoInteractions(logs, query);
    }

    @Test
    void unknownDestinationNeverQueriesDatabase() {
        var params = new LinkedMultiValueMap<String,String>(); params.add("destino", "unknown");
        assertThrows(ResponseStatusException.class, () -> service.consultarIntegracoesClientes(0,10,null,null,params));
        verifyNoInteractions(logs, query);
    }

    @Test
    void dailyEvolutionKeepsDateAndHandlesMissingCounts() {
        var row = mock(LogIntegracaoRepository.IntegracaoEvolucaoDiariaProjection.class);
        when(row.getData()).thenReturn(LocalDate.of(2026,9,9));
        when(logs.buscarEvolucaoDiariaIntegracoes(any(),any(),any())).thenReturn(List.of(row));
        assertEquals(1, service.consultarEvolucaoDiaria("2026-09-01","2026-09-09",List.of()).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"foto", "imagemBase64", "imagem", "imageBase64", "canhotoBase64", "conteudoBase64"})
    void readsExistingLegacyImageWithoutContactingOrigin(String field) {
        when(logs.findById(1L)).thenReturn(Optional.of(LogIntegracaoModel.builder().sistemaDestino("PPG")
                .requestPayload("{\"" + field + "\":\"data:image\\/jpeg;base64,unit\"}").build()));
        assertEquals("data:image/jpeg;base64,unit", service.buscarImagemCanhoto(1L).orElseThrow());
        verifyNoInteractions(query, work);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "{}", "{\"foto\":\"null\"}"})
    void missingLegacyImageDoesNotInventContent(String payload) {
        when(logs.findById(1L)).thenReturn(Optional.of(LogIntegracaoModel.builder().sistemaDestino("PPG").requestPayload(payload).build()));
        assertTrue(service.buscarImagemCanhoto(1L).isEmpty());
    }

    @Test
    void doesNotExposeImageFromDifferentDestination() {
        when(logs.findById(1L)).thenReturn(Optional.of(LogIntegracaoModel.builder().sistemaDestino("VEDACIT").requestPayload("data:image/jpeg;base64,unit").build()));
        assertTrue(service.buscarImagemCanhoto(1L).isEmpty());
        assertTrue(service.buscarImagemCanhoto(null).isEmpty());
    }

    @Test
    void historyIsLimitedAndDoesNotWriteAudit() {
        when(work.buscarHistorico(any(),any(),any(),any(),anyInt(),anyInt()))
                .thenReturn(new WorkSftpClientesAuditoriaRepository.PaginaCiclos(List.of(),501));
        assertNotNull(service.consultarHistoricoWorkSftpClientes(-1,1000," VEDACIT ","FALHA","2026-09-01","2026-09-09"));
        verify(work).buscarHistorico(eq("VEDACIT"),eq("FALHA"),any(),any(),eq(0),eq(500));
        verify(work,never()).registrar(any());
    }
}
