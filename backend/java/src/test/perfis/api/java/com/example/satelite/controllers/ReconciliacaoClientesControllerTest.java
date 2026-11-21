package com.example.satelite.controllers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import com.example.satelite.repositories.ReconciliacaoLocalRepository;
import com.example.satelite.repositories.ReconciliacaoVedacitRepository;

class ReconciliacaoClientesControllerTest {
    final ReconciliacaoLocalRepository local=mock(ReconciliacaoLocalRepository.class);
    final ReconciliacaoVedacitRepository vedacit=mock(ReconciliacaoVedacitRepository.class);
    final ReconciliacaoClientesController controller=new ReconciliacaoClientesController(local,vedacit);
    @Test void consultaLocalEscopadaPeloCliente() {
        when(local.execucoes("PPG")).thenReturn(List.of(Map.of("cliente","PPG")));
        assertEquals(List.of(Map.of("cliente","PPG")),controller.execucoes("ppg"));
        controller.resumo("SELIA",10); verify(local).resumo("SELIA",10); verifyNoInteractions(vedacit);
    }
    @Test void preservaHistoricoVedacit() {
        controller.execucoes("VEDACIT"); controller.resumo("VEDACIT",10);
        verify(vedacit).execucoes(); verify(vedacit).resumo(10); verifyNoInteractions(local);
    }
    @Test void rejeitaClienteDesconhecidoAntesDoBanco() {
        assertThrows(ResponseStatusException.class,()->controller.execucoes("DESCONHECIDO"));
        verifyNoInteractions(local,vedacit);
    }
}
