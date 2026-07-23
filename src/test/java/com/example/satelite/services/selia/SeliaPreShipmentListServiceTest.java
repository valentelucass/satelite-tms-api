package com.example.satelite.services.selia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.satelite.dto.selia.SeliaPreShipmentInvoiceDTO;
import com.example.satelite.dto.selia.SeliaPreShipmentListRequestDTO;
import com.example.satelite.dto.selia.SeliaPreShipmentListResponseDTO;
import com.example.satelite.dto.selia.SeliaPreShipmentOrderDTO;
import com.example.satelite.dto.selia.SeliaPreShipmentVolumeDTO;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;

class SeliaPreShipmentListServiceTest {

    @Test
    void deveAceitarPlpPersistindoSomenteCorrelacaoTecnica() {
        LogIntegracaoRepository repository = org.mockito.Mockito.mock(LogIntegracaoRepository.class);
        when(repository.findTopBySistemaDestinoAndIntelipostPreShipmentListOrderByDataProcessamentoAscIdAsc(
                eq("SELIA_PLP"), eq(2970L)
        )).thenReturn(Optional.empty());
        AtomicLong sequence = new AtomicLong(700L);
        when(repository.save(any(LogIntegracaoModel.class))).thenAnswer(invocation -> {
            LogIntegracaoModel log = invocation.getArgument(0);
            if (log.getId() == null) {
                log.setId(sequence.getAndIncrement());
            }
            return log;
        });

        SeliaPreShipmentListService service = criarService(repository, true);

        SeliaPreShipmentListResponseDTO resposta = service.receber("lp-key-teste", requisicaoValida());

        assertEquals("OK", resposta.status());
        assertEquals(2970L, resposta.intelipostPreShipmentList());
        assertEquals(700L, resposta.logisticsProviderShipmentList());
        assertEquals("PEDIDO-123", resposta.orders().get(0).orderNumber());
        assertEquals("VOLUME-456", resposta.orders().get(0).shipmentOrderVolumes().get(0).shipmentOrderVolumeNumber());

        ArgumentCaptor<List<LogIntegracaoModel>> captor = captorDeCorrelacoes();
        verify(repository).saveAll(captor.capture());
        LogIntegracaoModel correlacao = captor.getValue().get(0);
        assertEquals("35260612345678000123550010000012341000012345", correlacao.getChaveNfe());
        assertEquals("PEDIDO-123", correlacao.getOrderNumber());
        assertEquals("VOLUME-456", correlacao.getVolumeNumber());
        assertEquals("SELIA_PLP_MAP", correlacao.getSistemaDestino());
        assertEquals("ACEITO_PLP", correlacao.getStatus());
        assertEquals(null, correlacao.getRequestPayload());
        assertEquals(null, correlacao.getResponsePayload());
    }

    @Test
    void deveRetornarMesmaListaParaPlpRepetidaSemCriarNovaCorrelacao() {
        LogIntegracaoRepository repository = org.mockito.Mockito.mock(LogIntegracaoRepository.class);
        LogIntegracaoModel existente = LogIntegracaoModel.builder()
                .id(700L)
                .intelipostPreShipmentList(2970L)
                .logisticsProviderShipmentList(700L)
                .dataProcessamento(LocalDateTime.of(2026, 7, 23, 10, 0))
                .build();
        when(repository.findTopBySistemaDestinoAndIntelipostPreShipmentListOrderByDataProcessamentoAscIdAsc(
                eq("SELIA_PLP"), eq(2970L)
        )).thenReturn(Optional.of(existente));

        SeliaPreShipmentListService service = criarService(repository, true);

        SeliaPreShipmentListResponseDTO resposta = service.receber("lp-key-teste", requisicaoValida());

        assertEquals("OK", resposta.status());
        assertEquals(700L, resposta.logisticsProviderShipmentList());
        verify(repository, never()).save(any(LogIntegracaoModel.class));
        verify(repository, never()).saveAll(any());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<LogIntegracaoModel>> captorDeCorrelacoes() {
        return (ArgumentCaptor<List<LogIntegracaoModel>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
    }

    @Test
    void deveRejeitarChaveInvalidaSemAcessarAuditoriaOuExporSegredo() {
        LogIntegracaoRepository repository = org.mockito.Mockito.mock(LogIntegracaoRepository.class);
        SeliaPreShipmentListService service = criarService(repository, true);

        SeliaPlpProcessingException erro = assertThrows(SeliaPlpProcessingException.class,
                () -> service.receber("chave-invalida", requisicaoValida()));

        assertEquals(HttpStatus.UNAUTHORIZED, erro.status());
        assertEquals("Não autorizado.", erro.getMessage());
        verify(repository, never()).findTopBySistemaDestinoAndIntelipostPreShipmentListOrderByDataProcessamentoAscIdAsc(
                any(), any()
        );
    }

    @Test
    void deveRejeitarListaIncompletaPorInteiro() {
        LogIntegracaoRepository repository = org.mockito.Mockito.mock(LogIntegracaoRepository.class);
        SeliaPreShipmentListService service = criarService(repository, true);
        SeliaPreShipmentListRequestDTO invalida = new SeliaPreShipmentListRequestDTO(
                2970L,
                List.of(new SeliaPreShipmentOrderDTO("PEDIDO-123", List.of()))
        );

        SeliaPlpProcessingException erro = assertThrows(SeliaPlpProcessingException.class,
                () -> service.receber("lp-key-teste", invalida));

        assertEquals(HttpStatus.BAD_REQUEST, erro.status());
        verify(repository, never()).save(any(LogIntegracaoModel.class));
    }

    private SeliaPreShipmentListService criarService(LogIntegracaoRepository repository, boolean enabled) {
        SeliaPreShipmentListService service = new SeliaPreShipmentListService(repository);
        ReflectionTestUtils.setField(service, "logisticProviderApiKey", "lp-key-teste");
        ReflectionTestUtils.setField(service, "plpEnabled", enabled);
        return service;
    }

    private SeliaPreShipmentListRequestDTO requisicaoValida() {
        return new SeliaPreShipmentListRequestDTO(
                2970L,
                List.of(new SeliaPreShipmentOrderDTO(
                        "PEDIDO-123",
                        List.of(new SeliaPreShipmentVolumeDTO(
                                "VOLUME-456",
                                List.of(new SeliaPreShipmentInvoiceDTO(
                                        "35260612345678000123550010000012341000012345"
                                ))
                        ))
                ))
        );
    }
}
