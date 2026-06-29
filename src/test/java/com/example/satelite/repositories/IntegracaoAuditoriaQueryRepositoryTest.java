package com.example.satelite.repositories;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.example.satelite.dto.auditoria.PendenciaDTO;
import com.example.satelite.repositories.IntegracaoAuditoriaQueryRepository.Filtros;

class IntegracaoAuditoriaQueryRepositoryTest {

    @Test
    void deveAplicarFiltroDePendenciasQuandoBuscaEstaVazia() {
        NamedParameterJdbcTemplate jdbcTemplate = criarJdbcTemplate();
        IntegracaoAuditoriaQueryRepository repository = new IntegracaoAuditoriaQueryRepository(jdbcTemplate);

        repository.buscarPendencias(filtros(null, null, null), 0, 20);

        String sql = capturarSqlTabela(jdbcTemplate);
        assertTrue(sql.contains("l.status_canhoto = 'PENDENTE_FOTO'"));
        assertTrue(sql.contains("l.status_dados = 'ERRO_DESTINO'"));
        assertTrue(sql.contains("possuiImagemPayload"));
        assertTrue(sql.contains("l.canhoto_referencia AS canhotoReferencia"));
        assertTrue(sql.contains("l.canhoto_mime_type AS canhotoMimeType"));
        assertTrue(sql.contains("l.canhoto_referencia IS NOT NULL"));
        assertFalse(sql.contains("request_payload"));
    }

    @Test
    void deveManterFiltroDePendenciasQuandoBuscaGeralEstaPreenchida() {
        NamedParameterJdbcTemplate jdbcTemplate = criarJdbcTemplate();
        IntegracaoAuditoriaQueryRepository repository = new IntegracaoAuditoriaQueryRepository(jdbcTemplate);

        repository.buscarPendencias(filtros("35260643996693000127550170004223891100032056", null, null), 0, 20);

        String sql = capturarSqlTabela(jdbcTemplate);
        assertTrue(sql.contains("l.status_canhoto = 'PENDENTE_FOTO'"));
        assertTrue(sql.contains("l.status_dados = 'ERRO_DESTINO'"));
        assertTrue(sql.contains("l.chave_nfe LIKE :filtroTabelaBusca"));
        assertTrue(sql.contains("possuiImagemPayload"));
        assertFalse(sql.contains("request_payload"));
    }

    @Test
    void deveManterFiltroDePendenciasQuandoCodigoEstaPreenchido() {
        NamedParameterJdbcTemplate jdbcTemplate = criarJdbcTemplate();
        IntegracaoAuditoriaQueryRepository repository = new IntegracaoAuditoriaQueryRepository(jdbcTemplate);

        repository.buscarPendencias(filtros(null, "423891", null), 0, 20);

        String sql = capturarSqlTabela(jdbcTemplate);
        assertTrue(sql.contains("l.status_canhoto = 'PENDENTE_FOTO'"));
        assertTrue(sql.contains("l.status_dados = 'ERRO_DESTINO'"));
        assertTrue(sql.contains("TRY_CAST(SUBSTRING(l.chave_nfe, 26, 9) AS BIGINT) = :filtroTabelaCodigoNumero"));
        assertTrue(sql.contains("possuiImagemPayload"));
        assertFalse(sql.contains("request_payload"));
    }

    @Test
    void deveAplicarFiltroDeSucessoQuandoEscopoForSucesso() {
        NamedParameterJdbcTemplate jdbcTemplate = criarJdbcTemplate();
        IntegracaoAuditoriaQueryRepository repository = new IntegracaoAuditoriaQueryRepository(jdbcTemplate);

        repository.buscarPendencias(filtros(null, null, "SUCESSO"), 0, 20);

        String sql = capturarSqlTabela(jdbcTemplate);
        assertTrue(sql.contains("l.status IN ('ENVIADO', 'PROCESSADO')"));
        assertTrue(sql.contains("l.status_dados IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')"));
        assertTrue(sql.contains("l.status_canhoto IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')"));
        assertFalse(sql.contains("l.status_canhoto = 'PENDENTE_FOTO'"));
        assertFalse(sql.contains("request_payload"));
    }

    @Test
    void deveIgnorarFiltroDeStatusOperacionalQuandoEscopoForTodos() {
        NamedParameterJdbcTemplate jdbcTemplate = criarJdbcTemplate();
        IntegracaoAuditoriaQueryRepository repository = new IntegracaoAuditoriaQueryRepository(jdbcTemplate);

        repository.buscarPendencias(filtros(null, null, "TODOS"), 0, 20);

        String sql = capturarSqlTabela(jdbcTemplate);
        assertTrue(sql.contains("l.sistema_destino IN ('VEDACIT', 'PPG')"));
        assertFalse(sql.contains("l.status_canhoto = 'PENDENTE_FOTO'"));
        assertFalse(sql.contains("l.status IN ('ENVIADO', 'PROCESSADO')"));
        assertFalse(sql.contains("request_payload"));
    }

    @Test
    void deveAplicarFiltroTemporalSargableQuandoPeriodoEstaPreenchido() {
        NamedParameterJdbcTemplate jdbcTemplate = criarJdbcTemplate();
        IntegracaoAuditoriaQueryRepository repository = new IntegracaoAuditoriaQueryRepository(jdbcTemplate);

        repository.buscarPendencias(filtrosComPeriodo("2026-06-01", "2026-06-30"), 0, 20);

        ConsultaTabela consulta = capturarConsultaTabela(jdbcTemplate);
        assertTrue(consulta.sql().contains("l.data_processamento >= :dataInicial"));
        assertTrue(consulta.sql().contains("l.data_processamento < :dataFinalLimit"));
        assertFalse(consulta.sql().contains("CAST(l.data_processamento AS DATE)"));
        assertEquals(LocalDateTime.of(2026, 6, 1, 0, 0), consulta.params().getValue("dataInicial"));
        assertEquals(LocalDateTime.of(2026, 7, 1, 0, 0), consulta.params().getValue("dataFinalLimit"));
    }

    private NamedParameterJdbcTemplate criarJdbcTemplate() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        when(jdbcTemplate.query(
                anyString(),
                any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<PendenciaDTO>>any()
        ))
                .thenReturn(List.<PendenciaDTO>of());
        return jdbcTemplate;
    }

    private String capturarSqlTabela(NamedParameterJdbcTemplate jdbcTemplate) {
        return capturarConsultaTabela(jdbcTemplate).sql();
    }

    private ConsultaTabela capturarConsultaTabela(NamedParameterJdbcTemplate jdbcTemplate) {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                paramsCaptor.capture(),
                ArgumentMatchers.<RowMapper<PendenciaDTO>>any()
        );
        return new ConsultaTabela(sqlCaptor.getValue(), paramsCaptor.getValue());
    }

    private Filtros filtros(String busca, String codigo, String escopo) {
        return new Filtros(busca, codigo, List.of(), Map.of(), escopo, null, null, null, null);
    }

    private Filtros filtrosComPeriodo(String dataInicial, String dataFinal) {
        return new Filtros(null, null, List.of(), Map.of(), null, null, null, dataInicial, dataFinal);
    }

    private record ConsultaTabela(String sql, MapSqlParameterSource params) {
    }
}
