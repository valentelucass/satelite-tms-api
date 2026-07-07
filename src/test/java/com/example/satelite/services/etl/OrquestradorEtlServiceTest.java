package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.extension.ExtendWith;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslItemDTO;
import com.example.satelite.dto.rodogarcia.EslFreightDTO;
import com.example.satelite.dto.rodogarcia.EslInvoiceDTO;
import com.example.satelite.dto.rodogarcia.EslLoteResponseDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.dto.rodogarcia.EslOccurrenceDefDTO;
import com.example.satelite.dto.rodogarcia.EslPagingDTO;
import com.example.satelite.models.ControleCursor;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.ControleCursorRepository;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.RetryableException;

@ExtendWith(OutputCaptureExtension.class)
class OrquestradorEtlServiceTest {

    private static final String URL_TESTE_E2E = "https://www.w3.org/People/mimasa/test/imgformat/img/w3c_home.jpg";

    @Test
    void deveBuscarOcorrenciasComUltimoCursorPersistido() {
        Dependencias dependencias = criarDependencias();
        ControleCursor cursor = ControleCursor.builder()
                .cursorNextId(123L)
                .sistemaDestino("PPG")
                .dataAtualizacao(LocalDateTime.now())
                .build();

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString()))
                .thenReturn(Optional.of(cursor));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), eq(123L), isNull(), isNull(), eq(1)))
                .thenReturn(loteVazio());
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-vedacit"), eq(123L), isNull(), isNull(), eq(1)))
                .thenReturn(loteVazio());

        dependencias.service().executarFluxos();

        verify(dependencias.rodogarciaClient()).buscarOcorrencias("Bearer token-ppg", 123L, null, null, 1);
        verify(dependencias.rodogarciaClient()).buscarOcorrencias("Bearer token-vedacit", 123L, null, null, 1);
        verify(dependencias.eslRequestPolicyService(), times(2)).executar(anyString(), any());
    }

    @Test
    void deveBuscarOcorrenciasIncrementaisComLookbackDe24Horas() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);
        ReflectionTestUtils.setField(dependencias.etlFluxoDestinoService(), "lookbackIncrementalHoras", 24);

        OffsetDateTime antes = OffsetDateTime.now(ZoneOffset.of("-03:00")).minusHours(24);
        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.rodogarciaClient().buscarOcorrencias(
                eq("Bearer token-ppg"),
                isNull(),
                isNull(),
                anyString(),
                eq(1)
        ))
                .thenReturn(loteVazio());

        dependencias.service().executarFluxos();

        ArgumentCaptor<String> sinceCaptor = ArgumentCaptor.forClass(String.class);
        verify(dependencias.rodogarciaClient()).buscarOcorrencias(
                eq("Bearer token-ppg"),
                isNull(),
                isNull(),
                sinceCaptor.capture(),
                eq(1)
        );

        OffsetDateTime depois = OffsetDateTime.now(ZoneOffset.of("-03:00")).minusHours(24);
        OffsetDateTime since = OffsetDateTime.parse(sinceCaptor.getValue());
        assertFalse(since.isBefore(antes.minusSeconds(1)));
        assertFalse(since.isAfter(depois.plusSeconds(1)));
    }

    @Test
    void deveRetomarBuscaQuandoEslRetorna429NaBuscaRaiz() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenThrow(criarErroTooManyRequestsEsl())
                .thenReturn(loteVazio());

        OrquestradorEtlService.ResultadoCiclo resultado = dependencias.service().executarFluxosComResultado();

        assertFalse(resultado.erroCritico());
        assertEquals(OrquestradorEtlService.CODIGO_SAIDA_SUCESSO, resultado.codigoSaida());
        verify(dependencias.rodogarciaClient(), times(2))
                .buscarOcorrencias("Bearer token-ppg", null, null, null, 1);
        verify(dependencias.eslRequestPolicyService(), times(1)).executar(anyString(), any());
    }

    @Test
    void deveMarcarFalhaDePaginaSemErroCriticoQuandoEslRetorna500HtmlNaBuscaRaiz() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenThrow(criarErroServidorEslHtml());

        OrquestradorEtlService.ResultadoCiclo resultado = dependencias.service().executarFluxosComResultado();

        assertFalse(resultado.erroCritico());
        assertEquals(OrquestradorEtlService.CODIGO_SAIDA_ERRO_CRITICO, resultado.codigoSaida());
        assertEquals(1, resultado.resultadoPpg().erros());
        assertEquals(1, resultado.resultadoPpg().paginasProcessadas());
        assertTrue(resultado.resultadoPpg().mensagemEncerramento().contains("cursor nao avancado"));
        verify(dependencias.controleCursorRepository(), times(0)).save(any());
    }

    @Test
    void deveMarcarFalhaDePaginaSemErroCriticoQuandoEslDaTimeoutNaBuscaRaiz() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenThrow(criarTimeoutEsl());

        OrquestradorEtlService.ResultadoCiclo resultado = dependencias.service().executarFluxosComResultado();

        assertFalse(resultado.erroCritico());
        assertEquals(OrquestradorEtlService.CODIGO_SAIDA_ERRO_CRITICO, resultado.codigoSaida());
        assertEquals(1, resultado.resultadoPpg().erros());
        assertEquals(1, resultado.resultadoPpg().paginasProcessadas());
        assertTrue(resultado.resultadoPpg().mensagemEncerramento().contains("cursor nao avancado"));
        verify(dependencias.controleCursorRepository(), times(0)).save(any());
    }

    @Test
    void deveBuscarVedacitPorInvoiceKeyQuandoWhitelistEstiverAtiva() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.etlFluxoDestinoService(), "vedacitNfeWhitelistEnabled", true);
        ReflectionTestUtils.setField(
                dependencias.etlFluxoDestinoService(),
                "vedacitNfeWhitelist",
                "35260660642774001209550010002214511591072444,35260660642774001209550010002214511591072445"
        );

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(loteVazio());
        when(dependencias.rodogarciaClient().buscarOcorrencias(
                eq("Bearer token-vedacit"),
                isNull(),
                eq("35260660642774001209550010002214511591072444"),
                isNull(),
                eq(1)
        ))
                .thenReturn(loteVazio());

        dependencias.service().executarFluxos();

        verify(dependencias.rodogarciaClient()).buscarOcorrencias("Bearer token-ppg", null, null, null, 1);
        verify(dependencias.rodogarciaClient()).buscarOcorrencias(
                "Bearer token-vedacit",
                null,
                "35260660642774001209550010002214511591072444",
                null,
                1
        );
    }

    @Test
    void deveBuscarPpgPorInvoiceKeyQuandoWhitelistEstiverAtiva() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.etlFluxoDestinoService(), "ppgNfeWhitelistEnabled", true);
        ReflectionTestUtils.setField(
                dependencias.etlFluxoDestinoService(),
                "ppgNfeWhitelist",
                "35260643996693000127550170004219491100020255,35260643996693000127550170004219491100020256"
        );

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.rodogarciaClient().buscarOcorrencias(
                eq("Bearer token-ppg"),
                isNull(),
                eq("35260643996693000127550170004219491100020255"),
                isNull(),
                eq(1)
        ))
                .thenReturn(loteVazio());
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-vedacit"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(loteVazio());

        dependencias.service().executarFluxos();

        verify(dependencias.rodogarciaClient()).buscarOcorrencias(
                "Bearer token-ppg",
                null,
                "35260643996693000127550170004219491100020255",
                null,
                1
        );
        verify(dependencias.rodogarciaClient()).buscarOcorrencias("Bearer token-vedacit", null, null, null, 1);
    }

    @Test
    void deveIgnorarPpgQuandoNotaNaoEstaNaWhitelistAntesDeBuscarComprovante() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.etlFluxoDestinoService(), "ppgNfeWhitelistEnabled", true);
        ReflectionTestUtils.setField(
                dependencias.etlFluxoDestinoService(),
                "ppgNfeWhitelist",
                "35260643996693000127550170004219491100020255"
        );

        when(dependencias.ppgIntegrationService().notaFiscalPermitida(any())).thenReturn(false);
        when(dependencias.ppgIntegrationService().processarOcorrencia(any(), isNull()))
                .thenReturn(ResultadoIntegracao.ignorado());
        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.controleCursorRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(
                eq("Bearer token-ppg"),
                isNull(),
                eq("35260643996693000127550170004219491100020255"),
                isNull(),
                eq(1)
        ))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(10L, 1, "35260612345678000123570010000012341000012345")),
                        new EslPagingDTO(99L, 1)
                ));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-vedacit"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(loteVazio());

        dependencias.service().executarFluxos();

        verify(dependencias.rodogarciaClient(), times(0)).buscarComprovante(anyString(), anyString());
        verify(dependencias.ppgIntegrationService(), times(0)).processarOcorrencia(any(), isNull());
    }

    @Test
    void deveMarcarPpgComoPendenteFotoQuandoComprovanteNaoExiste() {
        Dependencias dependencias = criarDependencias();
        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.controleCursorRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(10L, 1, "35260612345678000123570010000012341000012345")),
                        new EslPagingDTO(99L, 1)
                ));
        when(dependencias.rodogarciaClient().buscarComprovante(
                eq("Bearer token-ppg"),
                eq("35260612345678000123570010000012341000012345")
        ))
                .thenReturn(new ComprovanteEslDTO(List.of(), null));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-vedacit"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(loteVazio());

        dependencias.service().executarFluxos();

        ArgumentCaptor<com.example.satelite.models.LogIntegracaoModel> captor =
                ArgumentCaptor.forClass(com.example.satelite.models.LogIntegracaoModel.class);
        verify(dependencias.logIntegracaoRepository(), atLeastOnce()).save(captor.capture());

        assertTrue(captor.getAllValues().stream()
                .anyMatch(log -> "PENDENTE_FOTO".equals(log.getStatus())
                        && "PENDENTE_FOTO".equals(log.getStatusCanhoto())));
        verify(dependencias.ppgIntegrationService(), times(0)).processarOcorrencia(any(), any());
    }

    @Test
    void deveMarcarRegistroComoErroCanhotoQuandoEslRetorna500HtmlNoComprovante() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "ppgEnabled", false);

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-vedacit"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(10L, 1, "cte-10")),
                        new EslPagingDTO(99L, 1)
                ));
        when(dependencias.rodogarciaClient().buscarComprovante(eq("Bearer token-vedacit"), eq("cte-10")))
                .thenThrow(criarErroServidorEslHtml());

        OrquestradorEtlService.ResultadoCiclo resultado = dependencias.service().executarFluxosComResultado();

        assertFalse(resultado.erroCritico());
        assertEquals(1, resultado.resultadoVedacit().erros());

        ArgumentCaptor<LogIntegracaoModel> logCaptor = ArgumentCaptor.forClass(LogIntegracaoModel.class);
        verify(dependencias.logIntegracaoRepository(), atLeastOnce()).save(logCaptor.capture());
        assertTrue(logCaptor.getAllValues().stream()
                .anyMatch(log -> ResultadoIntegracao.STATUS_ERRO_DESTINO.equals(log.getStatusCanhoto())
                        && log.getMensagemErroCanhoto() != null
                        && log.getMensagemErroCanhoto().contains("HTTP 500")
                        && !log.getMensagemErroCanhoto().contains("<!doctype html>")));
        verify(dependencias.rodogarciaClient(), times(3)).buscarComprovante("Bearer token-vedacit", "cte-10");
        verify(dependencias.vedacitIntegrationService(), times(0))
                .processarOcorrencia(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void deveAvancarCursorQuandoPaginaNaoTemErros() {
        Dependencias dependencias = criarDependencias();
        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.controleCursorRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(10L, 110, "35260612345678000123570010000012341000012345")),
                        new EslPagingDTO(99L, 1)
                ));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-vedacit"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(loteVazio());

        dependencias.service().executarFluxos();

        ArgumentCaptor<ControleCursor> captor = ArgumentCaptor.forClass(ControleCursor.class);
        verify(dependencias.controleCursorRepository()).save(captor.capture());

        assertEquals("PPG", captor.getValue().getSistemaDestino());
        assertEquals(99L, captor.getValue().getCursorNextId());
    }

    @Test
    void deveContinuarPaginacaoAposLoteDePacingAteFimDaFila() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.controleCursorRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(10L, 1, "cte-10")),
                        new EslPagingDTO(99L, 1)
                ));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), eq(99L), isNull(), isNull(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(11L, 1, "cte-11")),
                        new EslPagingDTO(100L, 1)
                ));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), eq(100L), isNull(), isNull(), eq(1)))
                .thenReturn(loteVazio());
        when(dependencias.rodogarciaClient().buscarComprovante(anyString(), anyString()))
                .thenReturn(criarComprovanteComImagem());

        OrquestradorEtlService.ResultadoCiclo resultado = dependencias.service().executarFluxosComResultado();

        assertFalse(resultado.erroCritico());
        assertEquals(2, resultado.resultadoPpg().paginasProcessadas());
        assertEquals(2, resultado.resultadoPpg().enviados());
        verify(dependencias.rodogarciaClient()).buscarOcorrencias("Bearer token-ppg", null, null, null, 1);
        verify(dependencias.rodogarciaClient()).buscarOcorrencias("Bearer token-ppg", 99L, null, null, 1);
        verify(dependencias.rodogarciaClient()).buscarOcorrencias("Bearer token-ppg", 100L, null, null, 1);

        ArgumentCaptor<ControleCursor> captor = ArgumentCaptor.forClass(ControleCursor.class);
        verify(dependencias.controleCursorRepository(), times(2)).save(captor.capture());
        assertEquals(List.of(99L, 100L), captor.getAllValues().stream().map(ControleCursor::getCursorNextId).toList());
    }

    @Test
    void deveMarcarPendenteFotoQuandoCteAusenteSemConsumirErro() {
        Dependencias dependencias = criarDependencias();
        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.controleCursorRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(10L, 1, null)),
                        new EslPagingDTO(99L, 1)
                ));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-vedacit"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(loteVazio());

        dependencias.service().executarFluxos();

        ArgumentCaptor<LogIntegracaoModel> logCaptor = ArgumentCaptor.forClass(LogIntegracaoModel.class);
        verify(dependencias.logIntegracaoRepository(), atLeastOnce()).save(logCaptor.capture());
        assertTrue(logCaptor.getAllValues().stream()
                .anyMatch(log -> ResultadoIntegracao.STATUS_PENDENTE_FOTO.equals(log.getStatusCanhoto())
                        && Integer.valueOf(0).equals(log.getTentativasDados())
                        && Integer.valueOf(0).equals(log.getTentativasCanhoto())));
        verify(dependencias.rodogarciaClient(), times(0)).buscarComprovante(anyString(), anyString());
        verify(dependencias.ppgIntegrationService(), times(0)).processarOcorrencia(any(), any());
        verify(dependencias.controleCursorRepository(), times(1)).save(any());
    }

    @Test
    void devePausarERetentarMesmoRegistroQuandoErroDestinoForHttpTemporario() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.controleCursorRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(
                                criarOcorrencia(10L, 1, "cte-10"),
                                criarOcorrencia(11L, 1, "cte-11")
                        ),
                        new EslPagingDTO(99L, 2)
                ));
        when(dependencias.rodogarciaClient().buscarComprovante(anyString(), anyString()))
                .thenReturn(criarComprovanteComImagem());
        when(dependencias.ppgIntegrationService().processarOcorrencia(any(), any()))
                .thenReturn(ResultadoIntegracao.erroDados("HTTP 429 Too Many Requests"))
                .thenReturn(ResultadoIntegracao.enviado())
                .thenReturn(ResultadoIntegracao.enviado());

        dependencias.service().executarFluxos();

        ArgumentCaptor<EslOcorrenciaDTO> ocorrenciaCaptor = ArgumentCaptor.forClass(EslOcorrenciaDTO.class);
        verify(dependencias.ppgIntegrationService(), times(3)).processarOcorrencia(ocorrenciaCaptor.capture(), any());

        assertEquals(
                List.of(10L, 10L, 11L),
                ocorrenciaCaptor.getAllValues().stream().map(EslOcorrenciaDTO::id).toList()
        );
        verify(dependencias.controleCursorRepository(), times(1)).save(any());
    }

    @Test
    void deveConsumirLimiteDeRetentativasMesmoQuandoJpaMergeRetornaNovaInstancia() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.controleCursorRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> {
            LogIntegracaoModel log = invocation.getArgument(0);
            if (log.getId() == null) {
                log.setId(1L);
                return log;
            }
            return copiarLog(log);
        });
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(10L, 1, "cte-10")),
                        new EslPagingDTO(99L, 1)
                ));
        when(dependencias.rodogarciaClient().buscarComprovante(anyString(), anyString()))
                .thenReturn(criarComprovanteComImagem());
        when(dependencias.ppgIntegrationService().processarOcorrencia(any(), any()))
                .thenReturn(ResultadoIntegracao.erroDados("HTTP 429 Too Many Requests"))
                .thenReturn(ResultadoIntegracao.erroDados("HTTP 429 Too Many Requests"))
                .thenReturn(ResultadoIntegracao.erroDados("HTTP 429 Too Many Requests"))
                .thenReturn(ResultadoIntegracao.enviado());

        dependencias.service().executarFluxos();

        verify(dependencias.ppgIntegrationService(), times(3)).processarOcorrencia(any(), any());

        ArgumentCaptor<LogIntegracaoModel> logCaptor = ArgumentCaptor.forClass(LogIntegracaoModel.class);
        verify(dependencias.logIntegracaoRepository(), atLeastOnce()).save(logCaptor.capture());
        assertTrue(logCaptor.getAllValues().stream()
                .anyMatch(log -> ResultadoIntegracao.STATUS_ERRO_DESTINO.equals(log.getStatusDados())
                        && Integer.valueOf(3).equals(log.getTentativasDados())));
        verify(dependencias.controleCursorRepository(), times(1)).save(any());
    }

    @Test
    void deveAbrirCircuitBreakerAposDezFalhasInfraestruturaConsecutivasSemAvancarCursor() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);

        List<EslOcorrenciaDTO> ocorrencias = LongStream.rangeClosed(1, 11)
                .mapToObj(id -> criarOcorrencia(id, 1, "cte-" + id))
                .toList();

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.controleCursorRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(ocorrencias, new EslPagingDTO(99L, ocorrencias.size())));
        when(dependencias.rodogarciaClient().buscarComprovante(anyString(), anyString()))
                .thenReturn(criarComprovanteComImagem());
        when(dependencias.ppgIntegrationService().processarOcorrencia(any(), any()))
                .thenReturn(ResultadoIntegracao.erroDados("java.net.SocketTimeoutException: Read timed out"));

        OrquestradorEtlService.ResultadoCiclo resultado = dependencias.service().executarFluxosComResultado();

        assertTrue(resultado.erroCritico());
        assertTrue(resultado.resultadoPpg().mensagemEncerramento().contains("Circuit Breaker Aberto"));
        verify(dependencias.ppgIntegrationService(), times(30)).processarOcorrencia(any(), any());
        verify(dependencias.controleCursorRepository(), times(0)).save(any());
    }

    @Test
    void retroativoDeveAvancarEmMemoriaQuandoPaginaTemErroRegistrado() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);

        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(
                eq("Bearer token-ppg"),
                isNull(),
                isNull(),
                eq("2026-06-01T00:00:00.000-03:00"),
                eq(1)
        ))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(
                                10L,
                                1,
                                "cte-10",
                                "2026-06-17T10:30:00-03:00",
                                null
                        )),
                        new EslPagingDTO(99L, 1)
                ));
        when(dependencias.rodogarciaClient().buscarComprovante("Bearer token-ppg", "cte-10"))
                .thenReturn(criarComprovanteComImagem());
        when(dependencias.ppgIntegrationService().processarOcorrencia(any(), any()))
                .thenThrow(new RuntimeException("falha destino"));
        when(dependencias.rodogarciaClient().buscarOcorrencias(
                eq("Bearer token-ppg"),
                eq(99L),
                isNull(),
                eq("2026-06-01T00:00:00.000-03:00"),
                eq(1)
        ))
                .thenReturn(loteVazio());

        OrquestradorEtlService.ResultadoCiclo resultado = dependencias.service().executarFluxosComResultado(
                ExecucaoEtlRequest.retroativo(
                        LocalDate.of(2026, 6, 1),
                        LocalDate.of(2026, 6, 30),
                        "PPG",
                        2
                )
        );

        assertFalse(resultado.erroCritico());
        assertEquals(OrquestradorEtlService.CODIGO_SAIDA_ERRO_CRITICO, resultado.codigoSaida());
        verify(dependencias.rodogarciaClient()).buscarOcorrencias(
                "Bearer token-ppg",
                null,
                null,
                "2026-06-01T00:00:00.000-03:00",
                1
        );
        verify(dependencias.rodogarciaClient()).buscarOcorrencias(
                "Bearer token-ppg",
                99L,
                null,
                "2026-06-01T00:00:00.000-03:00",
                1
        );
        verify(dependencias.controleCursorRepository(), times(0)).save(any());
    }

    @Test
    void devePararRetryAutomaticoNaTerceiraFalhaDestinoELiberarCursor() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);

        LogIntegracaoModel logExistente = LogIntegracaoModel.builder()
                .id(1L)
                .occurrenceId(10L)
                .chaveNfe("35260612345678000123550010000012341000012345")
                .freightId(30L)
                .cursorNextId(99L)
                .status(ResultadoIntegracao.STATUS_ERRO_DESTINO)
                .statusDados(ResultadoIntegracao.STATUS_ERRO_DESTINO)
                .statusCanhoto(ResultadoIntegracao.STATUS_SUCESSO)
                .tentativasDados(2)
                .tentativasCanhoto(1)
                .sistemaDestino("PPG")
                .dataProcessamento(LocalDateTime.now())
                .build();

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.controleCursorRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.logIntegracaoRepository()
                .findTopBySistemaDestinoAndOccurrenceIdOrderByDataProcessamentoDescIdDesc("PPG", 10L))
                .thenReturn(Optional.of(logExistente));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(10L, 1, "35260612345678000123570010000012341000012345")),
                        new EslPagingDTO(99L, 1)
                ));
        when(dependencias.rodogarciaClient().buscarComprovante(
                eq("Bearer token-ppg"),
                eq("35260612345678000123570010000012341000012345")
        ))
                .thenReturn(criarComprovanteComImagem());
        when(dependencias.ppgIntegrationService().processarOcorrencia(any(), any()))
                .thenReturn(ResultadoIntegracao.erroDados("Destino indisponível"));

        dependencias.service().executarFluxos();

        ArgumentCaptor<LogIntegracaoModel> logCaptor = ArgumentCaptor.forClass(LogIntegracaoModel.class);
        verify(dependencias.logIntegracaoRepository(), atLeastOnce()).save(logCaptor.capture());

        assertTrue(logCaptor.getAllValues().stream()
                .anyMatch(log -> ResultadoIntegracao.STATUS_ERRO_DESTINO.equals(log.getStatusDados())
                        && Integer.valueOf(3).equals(log.getTentativasDados())));
        verify(dependencias.controleCursorRepository(), times(1)).save(any());
    }

    @Test
    void naoDeveReprocessarAutomaticamenteQuandoLimiteTentativasJaFoiAtingido() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);

        LogIntegracaoModel logExistente = LogIntegracaoModel.builder()
                .id(1L)
                .occurrenceId(10L)
                .chaveNfe("35260612345678000123550010000012341000012345")
                .freightId(30L)
                .cursorNextId(99L)
                .status(ResultadoIntegracao.STATUS_ERRO_DESTINO)
                .statusDados(ResultadoIntegracao.STATUS_ERRO_DESTINO)
                .statusCanhoto(ResultadoIntegracao.STATUS_SUCESSO)
                .tentativasDados(3)
                .tentativasCanhoto(1)
                .sistemaDestino("PPG")
                .dataProcessamento(LocalDateTime.now())
                .build();

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.controleCursorRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.logIntegracaoRepository()
                .findTopBySistemaDestinoAndOccurrenceIdOrderByDataProcessamentoDescIdDesc("PPG", 10L))
                .thenReturn(Optional.of(logExistente));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(10L, 1, "35260612345678000123570010000012341000012345")),
                        new EslPagingDTO(99L, 1)
                ));

        dependencias.service().executarFluxos();

        verify(dependencias.rodogarciaClient(), times(0)).buscarComprovante(anyString(), anyString());
        verify(dependencias.ppgIntegrationService(), times(0)).processarOcorrencia(any(), any());
        verify(dependencias.controleCursorRepository(), times(1)).save(any());
    }

    @Test
    void retroativoDeveUsarSinceENaoLerOuSalvarControleCursorNemPendencias() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(
                eq("Bearer token-ppg"),
                isNull(),
                isNull(),
                eq("2026-05-01T00:00:00.000-03:00"),
                eq(1)
        ))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(
                                10L,
                                1,
                                "35260612345678000123570010000012341000012345",
                                "2026-05-03T10:30:00-03:00",
                                "2026-05-03T11:00:00-03:00"
                        )),
                        new EslPagingDTO(99L, 1)
                ));
        when(dependencias.rodogarciaClient().buscarComprovante(
                eq("Bearer token-ppg"),
                eq("35260612345678000123570010000012341000012345")
        ))
                .thenReturn(new ComprovanteEslDTO(
                        List.of(new ComprovanteEslItemDTO(50L, "https://example.com/canhoto.jpg", null, null, null)),
                        null
                ));

        OrquestradorEtlService.ResultadoCiclo resultado = dependencias.service().executarFluxosComResultado(
                ExecucaoEtlRequest.retroativo(
                        LocalDate.of(2026, 5, 1),
                        LocalDate.of(2026, 6, 23),
                        "PPG",
                        1
                )
        );

        assertFalse(resultado.erroCritico());
        verify(dependencias.controleCursorRepository(), times(0)).findBySistemaDestino(anyString());
        verify(dependencias.controleCursorRepository(), times(0)).save(any());
        verify(dependencias.logIntegracaoRepository(), times(0))
                .findBySistemaDestinoAndStatusCanhotoOrderByDataProcessamentoAscIdAsc(anyString(), anyString());
        verify(dependencias.rodogarciaClient()).buscarOcorrencias(
                "Bearer token-ppg",
                null,
                null,
                "2026-05-01T00:00:00.000-03:00",
                1
        );
    }

    @Test
    void retroativoDeveEncerrarQuandoPaginaUltrapassaDataFinalSemEnviarAoDestino(CapturedOutput output) {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);
        when(dependencias.rodogarciaClient().buscarOcorrencias(
                eq("Bearer token-ppg"),
                isNull(),
                isNull(),
                eq("2026-06-01T00:00:00.000-03:00"),
                eq(1)
        ))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(
                                10L,
                                1,
                                "35260612345678000123570010000012341000012345",
                                "2026-06-06T10:30:00-03:00",
                                "2026-06-06T11:00:00-03:00"
                        )),
                        new EslPagingDTO(99L, 1)
                ));

        OrquestradorEtlService.ResultadoCiclo resultado = dependencias.service().executarFluxosComResultado(
                ExecucaoEtlRequest.retroativo(
                        LocalDate.of(2026, 6, 1),
                        LocalDate.of(2026, 6, 5),
                        "PPG",
                        10
                )
        );

        assertFalse(resultado.erroCritico());
        assertTrue(output.getOut().contains("Fim da janela retroativa"));
        verify(dependencias.rodogarciaClient(), times(0)).buscarComprovante(anyString(), anyString());
        verify(dependencias.ppgIntegrationService(), times(0)).processarOcorrencia(any(), any());
        verify(dependencias.controleCursorRepository(), times(0)).save(any());
        verify(dependencias.logIntegracaoRepository(), times(0)).save(any());
    }

    @Test
    void retroativoDevePararNoPrimeiroItemForaDaJanelaSemProcessarRestanteDaPagina(CapturedOutput output) {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(
                eq("Bearer token-ppg"),
                isNull(),
                isNull(),
                eq("2026-06-01T00:00:00.000-03:00"),
                eq(1)
        ))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(
                                criarOcorrencia(
                                        10L,
                                        1,
                                        "35260612345678000123570010000012341000012345",
                                        "2026-06-04T10:30:00-03:00",
                                        "2026-06-04T11:00:00-03:00"
                                ),
                                criarOcorrencia(
                                        11L,
                                        1,
                                        "35260612345678000123570010000099991000099999",
                                        "2026-06-06T10:30:00-03:00",
                                        "2026-06-06T11:00:00-03:00"
                                )
                        ),
                        new EslPagingDTO(99L, 2)
                ));
        when(dependencias.rodogarciaClient().buscarComprovante(
                eq("Bearer token-ppg"),
                eq("35260612345678000123570010000012341000012345")
        ))
                .thenReturn(new ComprovanteEslDTO(
                        List.of(new ComprovanteEslItemDTO(50L, "https://example.com/canhoto.jpg", null, null, null)),
                        null
                ));

        OrquestradorEtlService.ResultadoCiclo resultado = dependencias.service().executarFluxosComResultado(
                ExecucaoEtlRequest.retroativo(
                        LocalDate.of(2026, 6, 1),
                        LocalDate.of(2026, 6, 5),
                        "PPG",
                        10
                )
        );

        assertFalse(resultado.erroCritico());
        assertTrue(output.getOut().contains("Fim da janela retroativa"));
        verify(dependencias.rodogarciaClient()).buscarComprovante(
                "Bearer token-ppg",
                "35260612345678000123570010000012341000012345"
        );
        verify(dependencias.rodogarciaClient(), times(0)).buscarComprovante(
                "Bearer token-ppg",
                "35260612345678000123570010000099991000099999"
        );
        verify(dependencias.ppgIntegrationService(), times(1)).processarOcorrencia(any(), any());
        verify(dependencias.controleCursorRepository(), times(0)).save(any());
    }

    @Test
    void retroativoDeveTolerarDatasNulasNaOcorrencia() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(
                eq("Bearer token-ppg"),
                isNull(),
                isNull(),
                eq("2026-06-01T00:00:00.000-03:00"),
                eq(1)
        ))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(criarOcorrencia(
                                10L,
                                1,
                                "35260612345678000123570010000012341000012345",
                                null,
                                null
                        )),
                        new EslPagingDTO(99L, 1)
                ));
        when(dependencias.rodogarciaClient().buscarComprovante(
                eq("Bearer token-ppg"),
                eq("35260612345678000123570010000012341000012345")
        ))
                .thenReturn(new ComprovanteEslDTO(
                        List.of(new ComprovanteEslItemDTO(50L, "https://example.com/canhoto.jpg", null, null, null)),
                        null
                ));

        OrquestradorEtlService.ResultadoCiclo resultado = dependencias.service().executarFluxosComResultado(
                ExecucaoEtlRequest.retroativo(
                        LocalDate.of(2026, 6, 1),
                        LocalDate.of(2026, 6, 5),
                        "PPG",
                        1
                )
        );

        assertFalse(resultado.erroCritico());
        verify(dependencias.ppgIntegrationService(), times(1)).processarOcorrencia(any(), any());
        verify(dependencias.controleCursorRepository(), times(0)).save(any());
    }

    @Test
    void retroativoDeveRejeitarDataInvalidaComMensagemLegivel() {
        IllegalArgumentException erro = assertThrows(
                IllegalArgumentException.class,
                () -> CicloRetroativoEtlRunner.parseDataObrigatoria("retroactive.start", "32/13/2026")
        );

        assertTrue(erro.getMessage().contains("--retroactive.start"));
        assertTrue(erro.getMessage().contains("AAAA-MM-DD"));
    }

    @Test
    void deveEncerrarComSucessoQuandoApiEslRetornaMesmoNextIdDaRequisicao(CapturedOutput output) {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "maxPaginasPorCiclo", 10);
        ReflectionTestUtils.setField(dependencias.service(), "vedacitEnabled", false);

        EslLoteResponseDTO primeiraPagina = new EslLoteResponseDTO(
                List.of(criarOcorrencia(10L, 1, "35260612345678000123570010000012341000012345")),
                new EslPagingDTO(99L, 1)
        );
        EslLoteResponseDTO paginaTravada = new EslLoteResponseDTO(
                List.of(criarOcorrencia(10L, 1, "35260612345678000123570010000012341000012345")),
                new EslPagingDTO(99L, 1)
        );

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.controleCursorRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(primeiraPagina);
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), eq(99L), isNull(), isNull(), eq(1)))
                .thenReturn(paginaTravada);

        OrquestradorEtlService.ResultadoCiclo resultado = dependencias.service().executarFluxosComResultado();

        assertFalse(resultado.erroCritico());
        assertEquals(OrquestradorEtlService.CODIGO_SAIDA_SUCESSO, resultado.codigoSaida());
        assertTrue(output.getOut().contains("Fim de fila detectado"));
        assertTrue(output.getOut().contains("cursor repetido 99"));
        assertFalse(output.getOut().contains("API da ESL travou"));
        assertFalse(output.getOut().contains("Loop crítico de paginação ESL"));
        verify(dependencias.rodogarciaClient()).buscarOcorrencias("Bearer token-ppg", null, null, null, 1);
        verify(dependencias.rodogarciaClient()).buscarOcorrencias("Bearer token-ppg", 99L, null, null, 1);
        verify(dependencias.controleCursorRepository(), times(1)).save(any());
    }

    @Test
    void deveEncerrarComSucessoQuandoCursorNaoAvancaSemTrabalhoUtil(CapturedOutput output) {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.service(), "maxPaginasPorCiclo", 10);
        ReflectionTestUtils.setField(dependencias.service(), "ppgEnabled", false);
        ReflectionTestUtils.setField(dependencias.etlFluxoDestinoService(), "vedacitNfeWhitelistEnabled", true);
        ReflectionTestUtils.setField(
                dependencias.etlFluxoDestinoService(),
                "vedacitNfeWhitelist",
                "35260660642774001209550010002214511591072444"
        );

        ControleCursor cursor = ControleCursor.builder()
                .cursorNextId(99L)
                .sistemaDestino("VEDACIT")
                .dataAtualizacao(LocalDateTime.now())
                .build();
        EslLoteResponseDTO paginaSemTrabalhoUtil = new EslLoteResponseDTO(
                List.of(criarOcorrencia(10L, 1, "35260612345678000123570010000012341000012345")),
                new EslPagingDTO(99L, 1)
        );

        when(dependencias.controleCursorRepository().findBySistemaDestino("VEDACIT"))
                .thenReturn(Optional.of(cursor));
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.vedacitIntegrationService().notaFiscalPermitida(any())).thenReturn(false);
        when(dependencias.rodogarciaClient().buscarOcorrencias(
                eq("Bearer token-vedacit"),
                eq(99L),
                eq("35260660642774001209550010002214511591072444"),
                isNull(),
                eq(1)
        ))
                .thenReturn(paginaSemTrabalhoUtil);

        OrquestradorEtlService.ResultadoCiclo resultado = dependencias.service().executarFluxosComResultado();

        assertFalse(resultado.erroCritico());
        assertEquals(OrquestradorEtlService.CODIGO_SAIDA_SUCESSO, resultado.codigoSaida());
        assertTrue(output.getOut().contains("Fim de fila detectado"));
        assertTrue(output.getOut().contains("cursor repetido 99"));
        verify(dependencias.controleCursorRepository(), times(0)).save(any());
        verify(dependencias.rodogarciaClient(), times(0)).buscarComprovante(anyString(), anyString());
        verify(dependencias.vedacitIntegrationService(), times(0))
                .processarOcorrencia(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void modoE2eDeveProcessarPrimeiraNotaComImagemDeTesteQuandoComprovanteNaoTemUrl() {
        Dependencias dependencias = criarDependencias();
        ReflectionTestUtils.setField(dependencias.etlRegistroService(), "modoTesteE2eImagem", true);
        ReflectionTestUtils.setField(dependencias.etlRegistroService(), "urlImagemTesteE2e", URL_TESTE_E2E);

        when(dependencias.controleCursorRepository().findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(dependencias.logIntegracaoRepository().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-ppg"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(
                        List.of(
                                criarOcorrencia(10L, 110, "35260612345678000123570010000012341000012345"),
                                criarOcorrencia(11L, 120, "35260612345678000123570010000012351000012345")
                        ),
                        new EslPagingDTO(99L, 2)
                ));
        when(dependencias.rodogarciaClient().buscarOcorrencias(eq("Bearer token-vedacit"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(loteVazio());
        when(dependencias.rodogarciaClient().buscarComprovante(
                eq("Bearer token-ppg"),
                eq("35260612345678000123570010000012341000012345")
        ))
                .thenReturn(new ComprovanteEslDTO(
                        List.of(new ComprovanteEslItemDTO(50L, null, null, null, null)),
                        null
                ));

        dependencias.service().executarFluxos();

        ArgumentCaptor<ComprovanteEslDTO> comprovanteCaptor = ArgumentCaptor.forClass(ComprovanteEslDTO.class);
        verify(dependencias.ppgIntegrationService(), times(1)).processarOcorrencia(any(), comprovanteCaptor.capture());

        assertEquals(URL_TESTE_E2E, comprovanteCaptor.getValue().data().get(0).imageUrl());
    }

    private Dependencias criarDependencias() {
        RodogarciaClient rodogarciaClient = mock(RodogarciaClient.class);
        PpgIntegrationService ppgIntegrationService = mock(PpgIntegrationService.class);
        VedacitIntegrationService vedacitIntegrationService = mock(VedacitIntegrationService.class);
        LogIntegracaoRepository logIntegracaoRepository = mock(LogIntegracaoRepository.class);
        ControleCursorRepository controleCursorRepository = mock(ControleCursorRepository.class);
        EslRequestPolicyService eslRequestPolicyService = mock(EslRequestPolicyService.class);
        when(eslRequestPolicyService.executar(anyString(), any())).thenAnswer(invocation -> {
            Supplier<?> chamada = invocation.getArgument(1);
            while (true) {
                try {
                    return chamada.get();
                } catch (RetryableException e) {
                    if (e.status() == 429) {
                        continue;
                    }

                    throw new EslRequestPolicyService.EslRequestTransientException(
                            invocation.getArgument(0),
                            EslRequestPolicyService.STATUS_SEM_RESPOSTA_HTTP,
                            "Timeout na comunicacao com a ESL em " + invocation.getArgument(0),
                            e
                    );
                } catch (FeignException e) {
                    if (e.status() == 429) {
                        continue;
                    }

                    if (e.status() >= 500 && e.status() <= 599) {
                        throw new EslRequestPolicyService.EslRequestTransientException(
                                invocation.getArgument(0),
                                e.status(),
                                e
                        );
                    }

                    throw e;
                }
            }
        });
        EtlResilienciaService etlResilienciaService = new EtlResilienciaService();
        EtlEstadoIntegracaoService etlEstadoIntegracaoService = new EtlEstadoIntegracaoService(logIntegracaoRepository);
        QuarentenaService quarentenaService = new QuarentenaService(logIntegracaoRepository);
        EtlRepescagemService etlRepescagemService = mock(EtlRepescagemService.class);
        EtlRegistroService etlRegistroService = new EtlRegistroService(
                rodogarciaClient,
                eslRequestPolicyService,
                etlResilienciaService,
                etlEstadoIntegracaoService,
                ppgIntegrationService,
                vedacitIntegrationService
        );
        EtlFluxoDestinoService etlFluxoDestinoService = new EtlFluxoDestinoService(
                rodogarciaClient,
                controleCursorRepository,
                eslRequestPolicyService,
                etlRegistroService
        );
        ReflectionTestUtils.setField(etlFluxoDestinoService, "lookbackIncrementalHoras", 0);
        ReflectionTestUtils.setField(etlFluxoDestinoService, "pausaPacingPaginacaoMs", 0L);
        when(ppgIntegrationService.notaFiscalPermitida(any())).thenReturn(true);
        when(ppgIntegrationService.processarOcorrencia(any(), any())).thenReturn(ResultadoIntegracao.enviado());
        when(vedacitIntegrationService.notaFiscalPermitida(any())).thenReturn(true);
        when(vedacitIntegrationService.processarOcorrencia(any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(ResultadoIntegracao.enviado());
        when(logIntegracaoRepository.findTopBySistemaDestinoAndOccurrenceIdOrderByDataProcessamentoDescIdDesc(
                anyString(),
                any()
        ))
                .thenReturn(Optional.empty());
        when(logIntegracaoRepository.findBySistemaDestinoAndStatusCanhotoOrderByDataProcessamentoAscIdAsc(
                anyString(),
                anyString()
        ))
                .thenReturn(List.of());
        when(logIntegracaoRepository.findQuarentenaByDestino(anyString())).thenReturn(List.of());

        OrquestradorEtlService service = new OrquestradorEtlService(
                ppgIntegrationService,
                vedacitIntegrationService,
                etlEstadoIntegracaoService,
                etlFluxoDestinoService,
                quarentenaService,
                etlRepescagemService
        );
        ReflectionTestUtils.setField(service, "tokenPpgEsl", "token-ppg");
        ReflectionTestUtils.setField(service, "tokenVedacitEsl", "token-vedacit");
        ReflectionTestUtils.setField(service, "maxPaginasPorCiclo", 1);
        ReflectionTestUtils.setField(etlResilienciaService, "backoffErroTransitorioMs", 0L);

        return new Dependencias(
                service,
                rodogarciaClient,
                ppgIntegrationService,
                vedacitIntegrationService,
                logIntegracaoRepository,
                controleCursorRepository,
                eslRequestPolicyService,
                etlResilienciaService,
                etlEstadoIntegracaoService,
                etlRegistroService,
                etlFluxoDestinoService,
                etlRepescagemService
        );
    }

    private EslLoteResponseDTO loteVazio() {
        return new EslLoteResponseDTO(List.of(), new EslPagingDTO(null, 0));
    }

    private FeignException criarErroTooManyRequestsEsl() {
        return criarErroEsl(429, "Too Many Requests", "rate limit");
    }

    private FeignException criarErroServidorEslHtml() {
        return criarErroEsl(500, "Internal Server Error", "<!doctype html><html>erro</html>");
    }

    private RetryableException criarTimeoutEsl() {
        return new RetryableException(
                -1,
                "Read timed out",
                Request.HttpMethod.GET,
                new SocketTimeoutException("Read timed out"),
                (Long) null,
                criarRequestEsl()
        );
    }

    private FeignException criarErroEsl(int status, String reason, String body) {
        Response response = Response.builder()
                .status(status)
                .reason(reason)
                .request(criarRequestEsl())
                .body(body, StandardCharsets.UTF_8)
                .build();

        return FeignException.errorStatus("RodogarciaClient#buscarOcorrencias", response);
    }

    private Request criarRequestEsl() {
        return Request.create(
                Request.HttpMethod.GET,
                "https://rodogarcia.eslcloud.com.br/api/customer/invoice_occurrences",
                Collections.emptyMap(),
                null,
                StandardCharsets.UTF_8,
                new RequestTemplate()
        );
    }

    private ComprovanteEslDTO criarComprovanteComImagem() {
        return new ComprovanteEslDTO(
                List.of(new ComprovanteEslItemDTO(50L, "https://example.com/canhoto.jpg", null, null, null)),
                null
        );
    }

    private EslOcorrenciaDTO criarOcorrencia(Long id, Integer codigoOcorrencia, String cteKey) {
        return criarOcorrencia(
                id,
                codigoOcorrencia,
                cteKey,
                "2026-06-17T10:30:00-03:00",
                null
        );
    }

    private EslOcorrenciaDTO criarOcorrencia(
            Long id,
            Integer codigoOcorrencia,
            String cteKey,
            String occurrenceAt,
            String createdAt
    ) {
        return new EslOcorrenciaDTO(
                id,
                occurrenceAt != null ? OffsetDateTime.parse(occurrenceAt) : null,
                createdAt != null ? OffsetDateTime.parse(createdAt) : null,
                new EslInvoiceDTO(20L, "35260612345678000123550010000012341000012345", "1", "1234"),
                new EslFreightDTO(30L, cteKey),
                new EslOccurrenceDefDTO(40L, codigoOcorrencia, "Ocorrência")
        );
    }

    private LogIntegracaoModel copiarLog(LogIntegracaoModel log) {
        LogIntegracaoModel copia = new LogIntegracaoModel();
        copia.setId(log.getId());
        copia.setOccurrenceId(log.getOccurrenceId());
        copia.setChaveNfe(log.getChaveNfe());
        copia.setFreightId(log.getFreightId());
        copia.setCursorNextId(log.getCursorNextId());
        copia.setStatus(log.getStatus());
        copia.setSistemaDestino(log.getSistemaDestino());
        copia.setRequestPayload(log.getRequestPayload());
        copia.setResponsePayload(log.getResponsePayload());
        copia.setErro(log.getErro());
        copia.setStatusDados(log.getStatusDados());
        copia.setStatusCanhoto(log.getStatusCanhoto());
        copia.setMensagemErroDados(log.getMensagemErroDados());
        copia.setMensagemErroCanhoto(log.getMensagemErroCanhoto());
        copia.setDataProcessamentoDados(log.getDataProcessamentoDados());
        copia.setDataProcessamentoCanhoto(log.getDataProcessamentoCanhoto());
        copia.setTentativasDados(log.getTentativasDados());
        copia.setTentativasCanhoto(log.getTentativasCanhoto());
        copia.setDataProcessamento(log.getDataProcessamento());
        return copia;
    }

    private record Dependencias(
            OrquestradorEtlService service,
            RodogarciaClient rodogarciaClient,
            PpgIntegrationService ppgIntegrationService,
            VedacitIntegrationService vedacitIntegrationService,
            LogIntegracaoRepository logIntegracaoRepository,
            ControleCursorRepository controleCursorRepository,
            EslRequestPolicyService eslRequestPolicyService,
            EtlResilienciaService etlResilienciaService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            EtlRegistroService etlRegistroService,
            EtlFluxoDestinoService etlFluxoDestinoService,
            EtlRepescagemService etlRepescagemService
    ) {
    }
}
