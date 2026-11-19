package com.example.satelite.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.etl.QuarentenaService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

class QuarentenaControllerTest {

    @Test
    void deveConsultarTodosOsDestinosQuandoNaoHaSelecao() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        when(repository.findErrosManuais(eq(List.of("PPG", "VEDACIT", "SELIA", "SUPPORTE")), any(PageRequest.class)))
                .thenReturn(Page.empty());
        QuarentenaController controller = controller(repository);

        controller.listarErrosManuais(0, 100, null);

        verify(repository).findErrosManuais(eq(List.of("PPG", "VEDACIT", "SELIA", "SUPPORTE")), any(PageRequest.class));
    }

    @Test
    void deveRepassarUmaOuMultiplasSelecoesDeDestino() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        when(repository.findErrosManuais(any(), any(PageRequest.class))).thenReturn(Page.<LogIntegracaoModel>empty());
        QuarentenaController controller = controller(repository);

        controller.listarErrosManuais(0, 100, List.of("SELIA"));
        controller.listarErrosManuais(0, 100, List.of("PPG", "VEDACIT"));

        verify(repository).findErrosManuais(eq(List.of("SELIA")), any(PageRequest.class));
        verify(repository).findErrosManuais(eq(List.of("PPG", "VEDACIT")), any(PageRequest.class));
    }

    @Test
    void deveRecusarDestinoInvalido() {
        QuarentenaController controller = controller(mock(LogIntegracaoRepository.class));

        ResponseStatusException erro = assertThrows(
                ResponseStatusException.class,
                () -> controller.listarErrosManuais(0, 100, List.of("SELIA_PLP"))
        );

        assertEquals(400, erro.getStatusCode().value());
    }

    @Test
    void deveBloquearComandoManualNoModoDashboard() {
        QuarentenaController controller = new QuarentenaController(
                new QuarentenaService(mock(LogIntegracaoRepository.class), null), true);

        ResponseStatusException erro = assertThrows(
                ResponseStatusException.class,
                () -> controller.reprocessar("VEDACIT")
        );

        assertEquals(403, erro.getStatusCode().value());
    }

    private QuarentenaController controller(LogIntegracaoRepository repository) {
        return new QuarentenaController(new QuarentenaService(repository, null));
    }
}
