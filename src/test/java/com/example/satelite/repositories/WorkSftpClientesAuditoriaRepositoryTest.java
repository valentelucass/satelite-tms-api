package com.example.satelite.repositories;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.example.satelite.dto.auditoria.WorkSftpClienteStatusDTO;

class WorkSftpClientesAuditoriaRepositoryTest {

    @Test
    void aplicaOrigemNaContagemENaPaginaSemReclassificarCiclosComoApi() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<WorkSftpClienteStatusDTO>>any())).thenReturn(List.of());
        var repository = new WorkSftpClientesAuditoriaRepository(jdbc);
        var pagina = repository.buscarHistorico(null, null, LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 10, 0, 0), 0, 10, "API_ESL");
        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pageSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForObject(countSql.capture(), params.capture(), eq(Long.class));
        verify(jdbc).query(pageSql.capture(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<WorkSftpClienteStatusDTO>>any());
        assertTrue(countSql.getValue().contains(":origem = 'SFTP'"));
        assertTrue(pageSql.getValue().contains(":origem = 'SFTP'"));
        org.junit.jupiter.api.Assertions.assertEquals("API_ESL", params.getValue().getValue("origem"));
        org.junit.jupiter.api.Assertions.assertEquals(0, pagina.totalElementos());
        assertTrue(pagina.itens().isEmpty());
    }

    @Test
    void historicoFiltraClienteStatusEPeriodoSemSelecionarDadosSensiveis() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<WorkSftpClienteStatusDTO>>any())).thenReturn(List.of());
        WorkSftpClientesAuditoriaRepository repository = new WorkSftpClientesAuditoriaRepository(jdbc);

        repository.buscarHistorico("VEDACIT", "CONCLUIDO", LocalDateTime.of(2026, 8, 1, 0, 0),
                LocalDateTime.of(2026, 8, 2, 0, 0), 0, 25, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<WorkSftpClienteStatusDTO>>any());
        assertTrue(sql.getValue().contains("e.sftp_cliente = :cliente"));
        assertTrue(sql.getValue().contains("e.status_ciclo = :status"));
        assertTrue(sql.getValue().contains("e.fim_em >= :inicio"));
        assertTrue(sql.getValue().contains("e.fim_em < :fimExclusivo"));
        assertTrue(sql.getValue().contains("ORDER BY e.fim_em DESC, e.id DESC"));
        assertFalse(sql.getValue().toLowerCase().contains("senha"));
        assertFalse(sql.getValue().toLowerCase().contains("chave_nfe"));
        assertFalse(sql.getValue().toLowerCase().contains("canhoto_referencia"));
    }
}
