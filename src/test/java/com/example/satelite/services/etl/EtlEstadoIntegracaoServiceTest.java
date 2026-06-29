package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.example.satelite.dto.rodogarcia.EslFreightDTO;
import com.example.satelite.dto.rodogarcia.EslInvoiceDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.dto.rodogarcia.EslOccurrenceDefDTO;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.ResultadoIntegracao;

class EtlEstadoIntegracaoServiceTest {

    @Test
    void deveBuscarLogExistentePorOccurrenceId() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        EtlEstadoIntegracaoService service = new EtlEstadoIntegracaoService(repository);
        LogIntegracaoModel log = LogIntegracaoModel.builder().id(1L).build();
        EslOcorrenciaDTO ocorrencia = criarOcorrencia();

        when(repository.findTopBySistemaDestinoAndOccurrenceIdOrderByDataProcessamentoDescIdDesc("PPG", 10L))
                .thenReturn(Optional.of(log));

        Optional<LogIntegracaoModel> resultado = service.buscarLogIntegracaoExistente("PPG", ocorrencia);

        assertTrue(resultado.isPresent());
        assertSame(log, resultado.get());
        verify(repository).findTopBySistemaDestinoAndOccurrenceIdOrderByDataProcessamentoDescIdDesc("PPG", 10L);
    }

    @Test
    void deveCriarLogComStatusInicial() {
        EtlEstadoIntegracaoService service = new EtlEstadoIntegracaoService(mock(LogIntegracaoRepository.class));

        LogIntegracaoModel log = service.criarLogComStatus(
                "VEDACIT",
                99L,
                criarOcorrencia(),
                ResultadoIntegracao.STATUS_RECEBIDO
        );

        assertEquals(10L, log.getOccurrenceId());
        assertEquals("35260612345678000123550010000012341000012345", log.getChaveNfe());
        assertEquals(30L, log.getFreightId());
        assertEquals(99L, log.getCursorNextId());
        assertEquals(ResultadoIntegracao.STATUS_RECEBIDO, log.getStatusDados());
        assertEquals(ResultadoIntegracao.STATUS_RECEBIDO, log.getStatusCanhoto());
        assertEquals(0, log.getTentativasDados());
        assertEquals(0, log.getTentativasCanhoto());
    }

    @Test
    void deveAplicarErroDestinoEIncrementarTentativaMesmoComStatusAnteriorIgual() {
        EtlEstadoIntegracaoService service = new EtlEstadoIntegracaoService(mock(LogIntegracaoRepository.class));
        LogIntegracaoModel log = LogIntegracaoModel.builder()
                .statusDados(ResultadoIntegracao.STATUS_ERRO_DESTINO)
                .statusCanhoto(ResultadoIntegracao.STATUS_SUCESSO)
                .tentativasDados(1)
                .tentativasCanhoto(0)
                .build();

        service.aplicarResultadoIntegracao(log, ResultadoIntegracao.erroDados("HTTP 502 Bad Gateway"));

        assertEquals(ResultadoIntegracao.STATUS_ERRO_DESTINO, log.getStatusDados());
        assertEquals(ResultadoIntegracao.STATUS_SUCESSO, log.getStatusCanhoto());
        assertEquals(2, log.getTentativasDados());
        assertEquals(0, log.getTentativasCanhoto());
        assertEquals("HTTP 502 Bad Gateway", log.getErro());
        assertNull(log.getDataProcessamentoCanhoto());
    }

    @Test
    void naoDeveIncrementarTentativaQuandoCanhotoFicaPendente() {
        EtlEstadoIntegracaoService service = new EtlEstadoIntegracaoService(mock(LogIntegracaoRepository.class));
        LogIntegracaoModel log = LogIntegracaoModel.builder()
                .statusDados(ResultadoIntegracao.STATUS_RECEBIDO)
                .statusCanhoto(ResultadoIntegracao.STATUS_RECEBIDO)
                .tentativasDados(0)
                .tentativasCanhoto(0)
                .build();

        service.aplicarResultadoIntegracao(log, ResultadoIntegracao.pendenteFotoPpg("cte ausente"));

        assertEquals(ResultadoIntegracao.STATUS_PENDENTE_FOTO, log.getStatusCanhoto());
        assertEquals(0, log.getTentativasDados());
        assertEquals(0, log.getTentativasCanhoto());
        assertNull(log.getDataProcessamentoCanhoto());
    }

    @Test
    void deveConverterResultadoIntegracaoParaResultadoRegistro() {
        EtlEstadoIntegracaoService service = new EtlEstadoIntegracaoService(mock(LogIntegracaoRepository.class));

        assertEquals(ResultadoRegistro.ENVIADO, service.converterResultadoRegistro(ResultadoIntegracao.enviado()));
        assertEquals(ResultadoRegistro.IGNORADO, service.converterResultadoRegistro(ResultadoIntegracao.ignorado()));
        assertEquals(
                ResultadoRegistro.PENDENTE_FOTO,
                service.converterResultadoRegistro(ResultadoIntegracao.pendenteFotoPpg("pendente"))
        );
        assertEquals(
                ResultadoRegistro.ERRO,
                service.converterResultadoRegistro(ResultadoIntegracao.erroDados("falha"))
        );
    }

    @Test
    void deveCriarErroGenericoPorDestinoEStatusDadosAtualOuSucesso() {
        EtlEstadoIntegracaoService service = new EtlEstadoIntegracaoService(mock(LogIntegracaoRepository.class));

        ResultadoIntegracao erroPpg = service.criarResultadoErroGenerico("PPG", new RuntimeException("falha"));
        ResultadoIntegracao erroVedacit = service.criarResultadoErroGenerico("VEDACIT", new RuntimeException("falha"));
        LogIntegracaoModel semStatusDados = new LogIntegracaoModel();
        LogIntegracaoModel comStatusDados = LogIntegracaoModel.builder()
                .statusDados(ResultadoIntegracao.STATUS_ERRO_DESTINO)
                .build();

        assertEquals(ResultadoIntegracao.STATUS_ERRO_DESTINO, erroPpg.statusDados());
        assertEquals(ResultadoIntegracao.STATUS_ERRO_DESTINO, erroPpg.statusCanhoto());
        assertEquals(ResultadoIntegracao.STATUS_ERRO_DESTINO, erroVedacit.statusDados());
        assertNull(erroVedacit.statusCanhoto());
        assertEquals(ResultadoIntegracao.STATUS_SUCESSO, service.statusDadosAtualOuSucesso(semStatusDados));
        assertEquals(ResultadoIntegracao.STATUS_ERRO_DESTINO, service.statusDadosAtualOuSucesso(comStatusDados));
    }

    private EslOcorrenciaDTO criarOcorrencia() {
        return new EslOcorrenciaDTO(
                10L,
                OffsetDateTime.parse("2026-06-17T10:30:00-03:00"),
                null,
                new EslInvoiceDTO(20L, "35260612345678000123550010000012341000012345", "1", "1234"),
                new EslFreightDTO(30L, "cte-10"),
                new EslOccurrenceDefDTO(40L, 1, "Ocorrência")
        );
    }
}
