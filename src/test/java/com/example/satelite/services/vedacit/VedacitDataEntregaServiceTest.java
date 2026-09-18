package com.example.satelite.services.vedacit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.EslFreightDTO;
import com.example.satelite.dto.rodogarcia.EslInvoiceDTO;
import com.example.satelite.dto.rodogarcia.EslLoteResponseDTO;
import com.example.satelite.dto.rodogarcia.EslOccurrenceDefDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.dto.rodogarcia.EslPagingDTO;
import com.example.satelite.services.etl.EslRequestContext;
import com.example.satelite.services.etl.EslRequestPolicyService;
import com.example.satelite.services.etl.EslRequestPolicyService.EslRequestTransientException;

import feign.FeignException;
import feign.Request;
import feign.Response;

class VedacitDataEntregaServiceTest {

    private static final String NFE = "35260912345678000123550010000012341000012345";
    private static final String CTE = "35260912345678000123570010000012341000012345";

    private final RodogarciaClient rodogarcia = mock(RodogarciaClient.class);
    private final EslRequestPolicyService politica = mock(EslRequestPolicyService.class);
    private final VedacitDataEntregaService service = new VedacitDataEntregaService(rodogarcia, politica);

    @BeforeEach
    void preparar() {
        ReflectionTestUtils.setField(service, "tokenVedacit", "token-de-teste");
        ReflectionTestUtils.setField(service, "maximoPaginas", 3);
        when(politica.executarComTelemetria(any(EslRequestContext.class), any())).thenAnswer(invocacao ->
                ((Supplier<?>) invocacao.getArgument(1)).get());
    }

    @Test
    void resolveEntregaExataNaPaginaPosteriorSemTocarCursorProdutivo() {
        when(rodogarcia.buscarOcorrencias(eq("Bearer token-de-teste"), isNull(), eq(NFE), isNull(), eq(1)))
                .thenReturn(lote(List.of(), 50L));
        when(rodogarcia.buscarOcorrencias(eq("Bearer token-de-teste"), eq(50L), eq(NFE), isNull(), eq(1)))
                .thenReturn(lote(List.of(entrega(900L, "2026-09-18T11:08:30-03:00")), null));

        VedacitDataEntregaService.Resolucao resolucao = service.resolver(NFE, CTE);

        assertEquals(VedacitDataEntregaService.Situacao.ENCONTRADA, resolucao.situacao());
        assertEquals(900L, resolucao.idEvento());
        assertEquals(OffsetDateTime.parse("2026-09-18T11:08:30-03:00"), resolucao.dataEntrega());
        verify(rodogarcia).buscarOcorrencias("Bearer token-de-teste", null, NFE, null, 1);
        verify(rodogarcia).buscarOcorrencias("Bearer token-de-teste", 50L, NFE, null, 1);
    }

    @Test
    void ignoraNfeOuCteNaoCorrelacionadosEOEventoQueNaoEEntrega() {
        EslOcorrenciaDTO outraNfe = ocorrencia(1L, NFE.replace('1', '2'), CTE, 1, "2026-09-18T11:08:30-03:00");
        EslOcorrenciaDTO outroCte = ocorrencia(2L, NFE, CTE.replace('1', '2'), 1, "2026-09-18T11:08:30-03:00");
        EslOcorrenciaDTO evento110 = ocorrencia(3L, NFE, CTE, 110, "2026-09-18T11:08:30-03:00");
        when(rodogarcia.buscarOcorrencias(any(), isNull(), eq(NFE), isNull(), eq(1)))
                .thenReturn(lote(List.of(outraNfe, outroCte, evento110), null));

        VedacitDataEntregaService.Resolucao resolucao = service.resolver(NFE, CTE);

        assertEquals(VedacitDataEntregaService.Situacao.AUSENTE, resolucao.situacao());
        assertEquals("DATA_ENTREGA_OCORRENCIA_NAO_ENCONTRADA", resolucao.motivo());
    }

    @Test
    void aceitaDuplicacaoDoMesmoInstanteSemInventarConflito() {
        when(rodogarcia.buscarOcorrencias(any(), isNull(), eq(NFE), isNull(), eq(1)))
                .thenReturn(lote(List.of(
                        entrega(11L, "2026-09-18T11:08:30-03:00"),
                        entrega(12L, "2026-09-18T11:08:30-03:00")
                ), null));

        VedacitDataEntregaService.Resolucao resolucao = service.resolver(NFE, CTE);

        assertEquals(VedacitDataEntregaService.Situacao.ENCONTRADA, resolucao.situacao());
        assertEquals(OffsetDateTime.parse("2026-09-18T11:08:30-03:00"), resolucao.dataEntrega());
    }

    @Test
    void retemEventosDeEntregaComDatasConflitantes() {
        when(rodogarcia.buscarOcorrencias(any(), isNull(), eq(NFE), isNull(), eq(1)))
                .thenReturn(lote(List.of(
                        entrega(11L, "2026-09-18T11:08:30-03:00"),
                        entrega(12L, "2026-09-18T13:08:30-03:00")
                ), null));

        assertEquals(VedacitDataEntregaService.Situacao.AMBIGUA, service.resolver(NFE, CTE).situacao());
    }

    @Test
    void retemDataNulaERespostaIncompleta() {
        when(rodogarcia.buscarOcorrencias(any(), isNull(), eq(NFE), isNull(), eq(1)))
                .thenReturn(lote(List.of(ocorrencia(11L, NFE, CTE, 1, null)), null));

        assertEquals(VedacitDataEntregaService.Situacao.SEM_DATA_VALIDA, service.resolver(NFE, CTE).situacao());

        when(rodogarcia.buscarOcorrencias(any(), isNull(), eq(NFE), isNull(), eq(1)))
                .thenReturn(new EslLoteResponseDTO(null, null));
        assertEquals(VedacitDataEntregaService.Situacao.CONSULTA_INCOMPLETA, service.resolver(NFE, CTE).situacao());
    }

    @Test
    void detectaCursorRepetidoSemDeclararAusencia() {
        when(rodogarcia.buscarOcorrencias(any(), isNull(), eq(NFE), isNull(), eq(1)))
                .thenReturn(lote(List.of(), 77L));
        when(rodogarcia.buscarOcorrencias(any(), eq(77L), eq(NFE), isNull(), eq(1)))
                .thenReturn(lote(List.of(), 77L));

        VedacitDataEntregaService.Resolucao resolucao = service.resolver(NFE, CTE);

        assertEquals(VedacitDataEntregaService.Situacao.CONSULTA_INCOMPLETA, resolucao.situacao());
        assertEquals("DATA_ENTREGA_PAGINACAO_CURSOR_REPETIDO", resolucao.motivo());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 503})
    void retemFalhasHttpDaOrigemSemPropagarPayloadOuEnviar(int status) {
        when(rodogarcia.buscarOcorrencias(any(), isNull(), eq(NFE), isNull(), eq(1)))
                .thenThrow(falhaHttp(status));

        VedacitDataEntregaService.Resolucao resolucao = service.resolver(NFE, CTE);

        assertEquals(VedacitDataEntregaService.Situacao.INDISPONIVEL, resolucao.situacao());
        assertEquals("DATA_ENTREGA_ESL_INDISPONIVEL_HTTP_" + status, resolucao.motivo());
    }

    @Test
    void retemTimeoutECredencialAusente() {
        when(rodogarcia.buscarOcorrencias(any(), isNull(), eq(NFE), isNull(), eq(1)))
                .thenThrow(new EslRequestTransientException("ocorrencia", -1, "timeout", null));
        assertEquals(VedacitDataEntregaService.Situacao.INDISPONIVEL, service.resolver(NFE, CTE).situacao());

        ReflectionTestUtils.setField(service, "tokenVedacit", "");
        assertEquals(VedacitDataEntregaService.Situacao.INDISPONIVEL, service.resolver(NFE, CTE).situacao());
    }

    private EslLoteResponseDTO lote(List<EslOcorrenciaDTO> dados, Long proximo) {
        return new EslLoteResponseDTO(dados, new EslPagingDTO(proximo, dados.size()));
    }

    private EslOcorrenciaDTO entrega(Long id, String data) {
        return ocorrencia(id, NFE, CTE, 1, data);
    }

    private EslOcorrenciaDTO ocorrencia(Long id, String nfe, String cte, int codigo, String data) {
        return new EslOcorrenciaDTO(
                id,
                data == null ? null : OffsetDateTime.parse(data),
                new EslInvoiceDTO(20L, nfe, "1", "1234"),
                new EslFreightDTO(30L, cte),
                new EslOccurrenceDefDTO(40L, codigo, "evento")
        );
    }

    private FeignException falhaHttp(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "https://esl.test/ocorrencias", Map.of(), null, null, null);
        Response response = Response.builder().status(status).reason("falha de teste").request(request).build();
        return FeignException.errorStatus("buscarOcorrencias", response);
    }
}
