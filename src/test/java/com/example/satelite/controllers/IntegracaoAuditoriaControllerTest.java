package com.example.satelite.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.satelite.dto.auditoria.WorkSftpClienteStatusDTO;
import com.example.satelite.dto.auditoria.WorkSftpClienteExecucoesPaginadasDTO;
import com.example.satelite.dto.auditoria.PaginacaoDTO;
import com.example.satelite.services.auditoria.IntegracaoAuditoriaService;

class IntegracaoAuditoriaControllerTest {

    @Test
    void deveExporSomenteResumoAgregadoDoSftp() {
        IntegracaoAuditoriaService service = mock(IntegracaoAuditoriaService.class);
        WorkSftpClienteStatusDTO resumo = new WorkSftpClienteStatusDTO(
                "VEDACIT", LocalDateTime.of(2026, 8, 20, 10, 0), LocalDateTime.of(2026, 8, 20, 10, 1),
                "OK", "CONCLUIDO", 10, 1, 2, 1, 1, 9, 0, 0, 60_000,
                LocalDateTime.of(2026, 8, 20, 10, 31));
        when(service.consultarStatusWorkSftpClientes()).thenReturn(List.of(resumo));

        List<WorkSftpClienteStatusDTO> resposta = new IntegracaoAuditoriaController(service)
                .consultarStatusVedacitSftp();

        assertEquals(List.of(resumo), resposta);
        assertEquals(15, WorkSftpClienteStatusDTO.class.getRecordComponents().length);
    }

    @Test
    void deveEncaminharHistoricoSftpSemDadosFiscais() {
        IntegracaoAuditoriaService service = mock(IntegracaoAuditoriaService.class);
        WorkSftpClienteExecucoesPaginadasDTO pagina = new WorkSftpClienteExecucoesPaginadasDTO(
                List.of(), new PaginacaoDTO(0, 25, 0, 0, true, true));
        when(service.consultarHistoricoWorkSftpClientes(0, 25, "VEDACIT", "CONCLUIDO", "2026-08-01", "2026-08-02"))
                .thenReturn(pagina);

        WorkSftpClienteExecucoesPaginadasDTO resposta = new IntegracaoAuditoriaController(service)
                .consultarExecucoesVedacitSftp(0, 25, "VEDACIT", "CONCLUIDO", "2026-08-01", "2026-08-02");

        assertEquals(pagina, resposta);
        assertEquals(15, WorkSftpClienteStatusDTO.class.getRecordComponents().length);
    }
}
