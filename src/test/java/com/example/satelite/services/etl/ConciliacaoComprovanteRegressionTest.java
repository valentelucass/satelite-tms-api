package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.services.origem.sftp.vedacit.*;

class ConciliacaoComprovanteRegressionTest {
    private static final String NFE = "3".repeat(44), CTE = "5".repeat(44);
    private static final LocalDateTime ANTIGA = LocalDateTime.of(2026, 8, 20, 12, 0);
    private final LogIntegracaoRepository repo = mock(LogIntegracaoRepository.class);
    private final EtlRegistroService envios = mock(EtlRegistroService.class);
    private final EtlEstadoIntegracaoService estado = new EtlEstadoIntegracaoService(repo);
    private final SftpDocumentoLockService lock = mock(SftpDocumentoLockService.class);
    private final EtlRepescagemService service = new EtlRepescagemService(repo, envios, estado, null, null, null, lock);
    private final VedacitSftpDocument documento = new VedacitSftpDocument(VedacitSftpDocument.Tipo.COMPROVANTE,
            "comprovantes/sintetico.jpg", CTE, NFE, 10L, Instant.EPOCH, null);

    private void preparar(LogIntegracaoModel atual, LogIntegracaoModel legado) {
        when(repo.buscarDataHoraServidor()).thenReturn(LocalDateTime.of(2026, 9, 11, 17, 0));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lock.executarComLock(anyString(), anyString(), anyString(), any()))
                .thenAnswer(i -> Optional.ofNullable(i.<Supplier<?>>getArgument(3).get()));
        when(repo.findTopBySistemaDestinoAndSftpClienteAndChaveNfeAndChaveCteOrderByDataProcessamentoDescIdDesc("VEDACIT", "VEDACIT", NFE, CTE))
                .thenReturn(Optional.of(atual));
        when(repo.findLegadoVedacitArquivadoComDadosSucesso(NFE, CTE)).thenReturn(Optional.of(legado));
        when(repo.findCandidatosSftpPorClienteENfes(anyString(), anyList(), any())).thenReturn(List.of());
        when(repo.findTecnicosSftpPorClienteENfes(anyString(), anyList(), any())).thenReturn(List.of());
    }

    private LogIntegracaoModel atual() {
        return LogIntegracaoModel.builder().id(1L).sistemaDestino("VEDACIT").sftpCliente("VEDACIT")
                .chaveNfe(NFE).chaveCte(CTE).status("ENVIADO").statusDados("SUCESSO").statusCanhoto("SUCESSO")
                .dataProcessamentoCanhoto(ANTIGA).tentativasCanhoto(3).canhotoClassificacaoOperacional("SUCESSO")
                .canhotoOrigem("SFTP").canhotoReferencia("comprovantes/original.jpg")
                .canhotoChaveCteEfetiva(CTE).canhotoClassificadoEm(ANTIGA).dataProcessamento(ANTIGA).build();
    }

    private LogIntegracaoModel legado(String status) {
        return LogIntegracaoModel.builder().id(2L).sistemaDestino("VEDACIT").chaveNfe(NFE).chaveCte(CTE)
                .statusDados("SUCESSO").statusCanhoto(status).dataProcessamentoDados(ANTIGA.minusDays(10)).arquivado(true).build();
    }

    @ParameterizedTest
    @CsvSource({"NAO_APLICAVEL,true", "PENDENTE_FOTO,true", "ERRO_DESTINO,true", "SUCESSO,true",
                "NAO_APLICAVEL,false", "PENDENTE_FOTO,false", "ERRO_DESTINO,false", "SUCESSO,false"})
    void recuperarXmlPreservaComprovanteConcluidoEmQuinhentasVarreduras(String statusLegado, boolean comData) {
        var atual = atual();
        if (!comData) atual.setDataProcessamentoCanhoto(null);
        preparar(atual, legado(statusLegado));
        for (int i = 0; i < 500; i++) {
            var resultado = service.processarClienteSftpVedacit("VEDACIT", new VedacitSftpInventory(List.of(documento), List.of()),
                    mock(VedacitSftpDocumentSource.class), 10, 0);
            assertEquals(0, resultado.processamento().enviados());
            assertEquals(1, resultado.inventario().jaEnviados());
            assertEquals("SUCESSO", atual.getStatusCanhoto());
            assertEquals(comData ? ANTIGA : null, atual.getDataProcessamentoCanhoto());
        }
        assertEquals(ANTIGA.minusDays(10), atual.getDataProcessamentoDados());
        assertEquals(3, atual.getTentativasCanhoto());
        assertEquals(CTE, atual.getCanhotoChaveCteEfetiva());
        assertEquals(ANTIGA, atual.getCanhotoClassificadoEm());
        assertEquals("comprovantes/original.jpg", atual.getCanhotoReferencia());
        verifyNoInteractions(envios);
    }

    @ParameterizedTest
    @CsvSource({"TIMEOUT_AMBIGUO,NAO_APLICAVEL", "BLOQUEADO_DESTINO,PENDENTE_FOTO", "TIMEOUT_AMBIGUO,SUCESSO"})
    void legadoSemConfirmacaoDatadaNaoLiberaRestricaoAtual(String restricao, String statusLegado) throws Exception {
        var atual = atual(); atual.setStatusCanhoto("ERRO_DESTINO"); atual.setCanhotoClassificacaoOperacional(restricao);
        atual.setMensagemErroCanhoto("evidencia atual");
        preparar(atual, legado(statusLegado));
        reconciliar(atual, legado(statusLegado));
        assertEquals("ERRO_DESTINO", atual.getStatusCanhoto());
        assertEquals(restricao, atual.getCanhotoClassificacaoOperacional());
        assertEquals(ANTIGA, atual.getDataProcessamentoCanhoto());
        assertEquals("evidencia atual", atual.getMensagemErroCanhoto());
    }

    @Test
    void legadoComOutroCteEfetivoNaoConfirmaEsteComprovante() throws Exception {
        var atual = atual(); atual.setStatusCanhoto("PENDENTE_FOTO"); atual.setCanhotoClassificacaoOperacional("BLOQUEADO_ORIGEM");
        var legado = legado("SUCESSO"); legado.setCanhotoChaveCteEfetiva("7".repeat(44)); legado.setDataProcessamentoCanhoto(ANTIGA);
        preparar(atual, legado); reconciliar(atual, legado);
        assertEquals("PENDENTE_FOTO", atual.getStatusCanhoto());
        assertEquals("PENDENTE_ENVIO", atual.getCanhotoClassificacaoOperacional());
    }

    @Test
    void resultadoXmlNaoAlteraTentativaDataOuMensagemDeComprovanteEmErro() {
        var atual = atual(); atual.setStatusCanhoto("ERRO_DESTINO"); atual.setMensagemErroCanhoto("timeout anterior");
        when(repo.buscarDataHoraServidor()).thenReturn(ANTIGA.plusDays(10));
        for (int i=0; i<100; i++) estado.aplicarResultadoXml(atual, ResultadoIntegracao.erroDados("ORIGEM_XML_HTTP_401"));
        assertEquals(ANTIGA, atual.getDataProcessamentoCanhoto());
        assertEquals(3, atual.getTentativasCanhoto());
        assertEquals("timeout anterior", atual.getMensagemErroCanhoto());
    }

    @Test
    void resultadoGenericoNaoRebaixaComprovanteConcluido() {
        var atual = atual(); when(repo.buscarDataHoraServidor()).thenReturn(ANTIGA.plusDays(10));
        estado.aplicarResultadoIntegracao(atual, ResultadoIntegracao.erroCanhoto("SUCESSO", "erro posterior"));
        estado.classificarCanhotoVedacit(atual, ClassificacaoOperacionalCanhotoVedacit.PENDENTE_TECNICO);
        assertEquals("SUCESSO", atual.getStatusCanhoto()); assertEquals("SUCESSO", atual.getCanhotoClassificacaoOperacional());
        assertEquals(ANTIGA, atual.getDataProcessamentoCanhoto()); assertEquals(3, atual.getTentativasCanhoto());
    }

    private void reconciliar(LogIntegracaoModel atual, LogIntegracaoModel legado) throws Exception {
        Method m = EtlRepescagemService.class.getDeclaredMethod("reconciliarRegistroSftpComLegado", LogIntegracaoModel.class,
                VedacitSftpDocument.class, LogIntegracaoModel.class);
        m.setAccessible(true); m.invoke(service, atual, documento, legado);
    }
}
