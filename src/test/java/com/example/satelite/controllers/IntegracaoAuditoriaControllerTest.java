package com.example.satelite.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.satelite.dto.auditoria.WorkSftpClienteStatusDTO;
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
}
