package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.LocalDateTime;
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
    void devePriorizarChaveCteNaIdempotenciaDaVedacit() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        EtlEstadoIntegracaoService service = new EtlEstadoIntegracaoService(repository);
        LogIntegracaoModel log = LogIntegracaoModel.builder().id(1L).chaveCte("cte-10").build();

        when(repository.findTopBySistemaDestinoAndChaveCteOrderByDataProcessamentoDescIdDesc("VEDACIT", "cte-10"))
                .thenReturn(Optional.of(log));

        Optional<LogIntegracaoModel> resultado = service.buscarLogIntegracaoExistente("VEDACIT", criarOcorrencia());

        assertTrue(resultado.isPresent());
        assertSame(log, resultado.get());
        verify(repository).findTopBySistemaDestinoAndChaveCteOrderByDataProcessamentoDescIdDesc("VEDACIT", "cte-10");
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
        assertEquals("cte-10", log.getChaveCte());
        assertEquals(30L, log.getFreightId());
        assertEquals(99L, log.getCursorNextId());
        assertEquals(ResultadoIntegracao.STATUS_RECEBIDO, log.getStatusDados());
        assertEquals(ResultadoIntegracao.STATUS_RECEBIDO, log.getStatusCanhoto());
        assertEquals(0, log.getTentativasDados());
        assertEquals(0, log.getTentativasCanhoto());
    }

    @Test
    void deveUsarHoraDoSqlNaAuditoria() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        LocalDateTime horaSql = LocalDateTime.of(2026, 7, 29, 17, 4, 0);
        when(repository.buscarDataHoraServidor()).thenReturn(horaSql);
        EtlEstadoIntegracaoService service = new EtlEstadoIntegracaoService(
                repository,
                new AuditoriaDataHoraService(repository)
        );

        LogIntegracaoModel log = service.criarLogComStatus(
                "SELIA",
                99L,
                criarOcorrencia(),
                ResultadoIntegracao.STATUS_RECEBIDO
        );
        service.aplicarResultadoIntegracao(log, ResultadoIntegracao.enviado());

        assertEquals(horaSql, log.getDataProcessamento());
        assertEquals(horaSql, log.getDataProcessamentoDados());
        assertEquals(horaSql, log.getDataProcessamentoCanhoto());
        verify(repository, org.mockito.Mockito.times(2)).buscarDataHoraServidor();
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
    void naoDeveIncrementarTentativaQuandoXmlFicaPendenteNaOrigem() {
        EtlEstadoIntegracaoService service = new EtlEstadoIntegracaoService(mock(LogIntegracaoRepository.class));
        LogIntegracaoModel log = LogIntegracaoModel.builder()
                .statusDados(ResultadoIntegracao.STATUS_RECEBIDO)
                .statusCanhoto(ResultadoIntegracao.STATUS_NAO_APLICAVEL)
                .tentativasDados(0)
                .tentativasCanhoto(0)
                .build();

        service.aplicarResultadoIntegracao(
                log,
                ResultadoIntegracao.pendenteOrigemDados(ResultadoIntegracao.STATUS_NAO_APLICAVEL, "XML ausente")
        );

        assertEquals(ResultadoIntegracao.STATUS_PENDENTE_ORIGEM, log.getStatusDados());
        assertEquals(0, log.getTentativasDados());
        assertNull(log.getDataProcessamentoDados());
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
                ResultadoRegistro.PENDENTE_ORIGEM,
                service.converterResultadoRegistro(ResultadoIntegracao.pendenteOrigemDados(null, "XML ausente"))
        );
        assertEquals(
                ResultadoRegistro.ERRO,
                service.converterResultadoRegistro(ResultadoIntegracao.erroDados("falha"))
        );
    }

    @Test
    void deveLiberarSomenteIgnoradoSeliaSemRequestOuResponseParaReprocessamento() {
        EtlEstadoIntegracaoService service = new EtlEstadoIntegracaoService(mock(LogIntegracaoRepository.class));
        LogIntegracaoModel ignoradoSemEnvio = LogIntegracaoModel.builder()
                .status(ResultadoIntegracao.STATUS_IGNORADO)
                .build();
        LogIntegracaoModel ignoradoComRequest = LogIntegracaoModel.builder()
                .status(ResultadoIntegracao.STATUS_IGNORADO)
                .requestPayload("{\\\"evento\\\":true}")
                .build();

        assertTrue(service.deveReprocessarIgnoradoSemEnvio("SELIA", ignoradoSemEnvio));
        assertFalse(service.deveReprocessarIgnoradoSemEnvio("PPG", ignoradoSemEnvio));
        assertFalse(service.deveReprocessarIgnoradoSemEnvio("SELIA", ignoradoComRequest));
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
