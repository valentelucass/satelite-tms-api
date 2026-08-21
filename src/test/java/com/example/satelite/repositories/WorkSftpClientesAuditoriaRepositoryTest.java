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
    void historicoFiltraClienteStatusEPeriodoSemSelecionarDadosSensiveis() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<WorkSftpClienteStatusDTO>>any())).thenReturn(List.of());
        WorkSftpClientesAuditoriaRepository repository = new WorkSftpClientesAuditoriaRepository(jdbc);

        repository.buscarHistorico("VEDACIT", "CONCLUIDO", LocalDateTime.of(2026, 8, 1, 0, 0),
                LocalDateTime.of(2026, 8, 2, 0, 0), 0, 25);

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
