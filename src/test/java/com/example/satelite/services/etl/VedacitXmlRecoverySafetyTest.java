package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

class VedacitXmlRecoverySafetyTest {
    private static final String NFE = "1".repeat(44), CTE = "2".repeat(44);
    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 9, 11, 12, 0);
    private final LogIntegracaoRepository repo = mock(LogIntegracaoRepository.class);
    private final EtlEstadoIntegracaoService estado = new EtlEstadoIntegracaoService(repo);
    private final VedacitIntegrationService vedacit = mock(VedacitIntegrationService.class);
    private final SftpDocumentoLockService locks = mock(SftpDocumentoLockService.class);
    private final EtlRegistroService service = new EtlRegistroService(mock(RodogarciaClient.class),
            mock(EslRequestPolicyService.class), mock(EtlResilienciaService.class), estado,
            mock(PpgIntegrationService.class), vedacit);

    @BeforeEach void preparar() {
        ReflectionTestUtils.setField(service, "xmlDocumentoLockService", locks);
        when(repo.buscarDataHoraServidor()).thenReturn(AGORA);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(locks.executarComLock(any(), any(), any(), any())).thenAnswer(i -> {
            Supplier<ResultadoRegistro> acao = i.getArgument(3); return Optional.of(acao.get());
        });
    }
    @Test void recupera401AntigoPreservandoTimeoutDoComprovante() {
        var registro = erro("401 RodogarciaClient#buscarXmlCte");
        registro.setStatusCanhoto("ERRO_DESTINO");
        registro.setMensagemErroCanhoto("Read timed out");
        registro.setCanhotoClassificacaoOperacional("TIMEOUT_AMBIGUO");
        selecionar(registro);
        when(vedacit.reprocessarXmlCtePorChaves(NFE, CTE, "ERRO_DESTINO"))
                .thenReturn(new ResultadoIntegracao("ENVIADO", "SUCESSO", "NAO_APLICAVEL", null, null));
        var resultado = service.recuperarXmlFalhasOrigem(null, 10);
        assertEquals(1, resultado.enviados());
        assertEquals("SUCESSO", registro.getStatusDados());
        assertEquals("ERRO_DESTINO", registro.getStatusCanhoto());
        assertEquals("TIMEOUT_AMBIGUO", registro.getCanhotoClassificacaoOperacional());
        assertEquals("Read timed out", registro.getMensagemErroCanhoto());
        verify(repo).findXmlFalhaOrigemParaRecuperacao(eq(AGORA.minusMinutes(30)), argThat(p -> p.getPageSize() == 10));
    }
    @Test void relerSobLockImpedeReenvioDepoisDeOutraConfirmacao() {
        var antigo = erro("ORIGEM_XML_HTTP_401");
        selecionar(antigo);
        var confirmado = erro(null);
        confirmado.setStatusDados("SUCESSO"); confirmado.setDataProcessamentoDados(AGORA);
        when(repo.findById(1L)).thenReturn(Optional.of(confirmado));
        assertEquals(1, service.recuperarXmlFalhasOrigem(null, 10).ignorados());
        verifyNoInteractions(vedacit);
    }
    @Test void naoReenviaResultadoSoapAmbiguoNemRecusaDefinitiva() {
        assertEquals(ResultadoRegistro.IGNORADO, service.reprocessarXmlCteVedacitPorChave(erro("Read timed out")));
        assertEquals(ResultadoRegistro.IGNORADO, service.reprocessarXmlCteVedacitPorChave(erro("Vedacit recusou XML")));
        verifyNoInteractions(vedacit);
    }

    @Test void sucessoLegadoSemDataImpedeReenvioMesmoComFalhaAtivaMaisRecente() {
        when(repo.existsXmlVedacitSucessoSemData(CTE)).thenReturn(true);
        assertEquals(ResultadoRegistro.RETIDO, service.reprocessarXmlCteVedacitPorChave(erro("ORIGEM_XML_HTTP_401")));
        verifyNoInteractions(vedacit);
    }
    @Test void xmlAusenteMantemTentativaDatadaParaRecuperacaoFutura() {
        var registro = erro("ORIGEM_XML_HTTP_401");
        when(vedacit.reprocessarXmlCtePorChaves(NFE, CTE, "PENDENTE_FOTO"))
                .thenReturn(ResultadoIntegracao.pendenteOrigemDados("PENDENTE_FOTO", "Não localizado"));
        assertEquals(ResultadoRegistro.PENDENTE_ORIGEM, service.reprocessarXmlCteVedacitPorChave(registro));
        assertEquals("ORIGEM_XML_AUSENTE", registro.getMensagemErroDados());
        assertEquals(AGORA, registro.getDataProcessamentoDados());
    }
    @Test void comprovanteSemProvaXmlOuAmbiguoNaoEntraEmEnvio() {
        var registro = erro(null);
        registro.setStatusDados("SUCESSO"); registro.setDataProcessamentoDados(null);
        assertEquals(ResultadoRegistro.IGNORADO, service.reprocessarCanhotoVedacitPorCte(registro));
        registro.setDataProcessamentoDados(AGORA); registro.setStatusCanhoto("ERRO_DESTINO");
        registro.setCanhotoClassificacaoOperacional("TIMEOUT_AMBIGUO");
        assertEquals(ResultadoRegistro.IGNORADO, service.reprocessarCanhotoVedacitPorCte(registro));
        verifyNoInteractions(vedacit);
    }

    @Test void comprovanteHistoricoDoParNaoEReenviadoNemRecebeDataArtificial() {
        var registro = erro(null); registro.setStatusDados("SUCESSO");
        when(repo.existsCanhotoVedacitSucessoPorPar(NFE, CTE)).thenReturn(true);
        assertEquals(ResultadoRegistro.JA_PROCESSADO, service.reprocessarCanhotoVedacitPorCte(registro));
        assertNull(registro.getDataProcessamentoCanhoto());
        verifyNoInteractions(vedacit);
    }
    private void selecionar(LogIntegracaoModel r) {
        when(repo.findXmlFalhaOrigemParaRecuperacao(any(), any())).thenReturn(List.of(r));
        when(repo.findById(1L)).thenReturn(Optional.of(r));
    }
    private LogIntegracaoModel erro(String motivo) {
        return LogIntegracaoModel.builder().id(1L).sistemaDestino("VEDACIT").sftpCliente("VEDACIT")
                .chaveNfe(NFE).chaveCte(CTE).status("ERRO_DESTINO").statusDados("ERRO_DESTINO")
                .statusCanhoto("PENDENTE_FOTO").mensagemErroDados(motivo)
                .dataProcessamentoDados(AGORA.minusHours(1)).build();
    }
}
