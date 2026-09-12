package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.origem.sftp.vedacit.*;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

class VedacitFilaRecoveryTest {
    private final LogIntegracaoRepository repo = mock(LogIntegracaoRepository.class);
    private final EtlRegistroService registros = mock(EtlRegistroService.class);
    private final SftpDocumentoLockService locks = mock(SftpDocumentoLockService.class);
    private final EtlRepescagemService service = new EtlRepescagemService(repo, registros,
            new EtlEstadoIntegracaoService(repo), mock(PpgIntegrationService.class),
            mock(VedacitIntegrationService.class), null, locks);
    private final VedacitSftpDocumentSource fonte = mock(VedacitSftpDocumentSource.class);
    private static final String NFE = "1".repeat(44), CTE = "2".repeat(44);

    @org.junit.jupiter.api.BeforeEach
    void lockLivre() {
        when(locks.executarComLock(any(), any(), any(), any()))
                .thenAnswer(i -> Optional.ofNullable(i.<Supplier<?>>getArgument(3).get()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void uploadInstavelNuncaLiberaComprovanteSemXml(boolean porCliente) {
        var atual = LogIntegracaoModel.builder().sistemaDestino("VEDACIT").sftpCliente("VEDACIT")
                .chaveNfe(NFE).chaveCte(CTE).canhotoReferencia("comprovantes/a.jpg").build();
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repo.findTopBySistemaDestinoAndSftpClienteAndCanhotoReferenciaOrderByDataProcessamentoDescIdDesc(any(), any(), any())).thenReturn(Optional.of(atual));
        when(repo.findTopBySistemaDestinoAndCanhotoReferenciaOrderByDataProcessamentoDescIdDesc(any(), any())).thenReturn(Optional.of(atual));
        when(repo.findTopBySistemaDestinoAndSftpClienteAndChaveNfeAndChaveCteOrderByDataProcessamentoDescIdDesc(any(), any(), any(), any())).thenReturn(Optional.of(atual));
        when(repo.findTopBySistemaDestinoAndChaveCteOrderByDataProcessamentoDescIdDesc(any(), any())).thenReturn(Optional.of(atual));
        var rejeitado = new VedacitSftpInventory(List.of(), List.of(new VedacitSftpInventory.DocumentoRejeitado(
                "comprovantes/a.jpg", CTE, NFE, "Upload instável: arquivo ainda está na janela mínima de estabilidade")));
        sincronizar(porCliente, rejeitado);
        assertEquals("PENDENTE_ORIGEM", atual.getStatusDados());
        sincronizar(porCliente, new VedacitSftpInventory(List.of(documento(NFE, CTE)), List.of()));
        assertEquals("PENDENTE_ORIGEM", atual.getStatusDados());
        assertNull(atual.getDataProcessamentoDados());
        assertNotEquals("PENDENTE_ENVIO", atual.getCanhotoClassificacaoOperacional());
        verifyNoInteractions(registros, fonte);
    }

    @ParameterizedTest @ValueSource(strings = {"SUCESSO", "TIMEOUT_AMBIGUO", "BLOQUEADO_DESTINO"})
    void inventarioRejeitadoPreservaResultadoAnterior(String classificacao) {
        var data = LocalDateTime.of(2026, 9, 1, 10, 0);
        var atual = LogIntegracaoModel.builder().statusDados("SUCESSO").dataProcessamentoDados(data)
                .statusCanhoto(classificacao.equals("SUCESSO") ? "SUCESSO" : "ERRO_DESTINO")
                .canhotoClassificacaoOperacional(classificacao).build();
        when(repo.findTopBySistemaDestinoAndCanhotoReferenciaOrderByDataProcessamentoDescIdDesc(any(), any())).thenReturn(Optional.of(atual));
        service.sincronizarInventarioSftpVedacit(new VedacitSftpInventory(List.of(), List.of(
                new VedacitSftpInventory.DocumentoRejeitado("comprovantes/a.jpg", CTE, NFE, "Upload instável"))));
        assertEquals("SUCESSO", atual.getStatusDados());
        assertEquals(data, atual.getDataProcessamentoDados());
        assertEquals(classificacao, atual.getCanhotoClassificacaoOperacional());
        verify(repo, never()).save(any());
    }

    @Test void drenaMilEmLotesDeDezSemRepetirPrimeiroComArquivoAusente() {
        var massa = prepararMil();
        Set<String> tentadas = new HashSet<>();
        when(registros.reprocessarCanhotoVedacitPorCte(any(), eq(fonte))).thenAnswer(i -> {
            LogIntegracaoModel atual = i.getArgument(0);
            assertTrue(tentadas.add(atual.getChaveNfe()), "Uma NF não pode ser tentada duas vezes na passagem");
            if (atual.getId() == 1L) return ResultadoRegistro.PENDENTE_FOTO;
            atual.setStatusCanhoto("SUCESSO");
            return ResultadoRegistro.ENVIADO;
        });
        var resultado = service.processarClienteSftpVedacit("VEDACIT", massa, fonte, 10, 0);
        assertEquals(1000, resultado.processamento().selecionados());
        assertEquals(999, resultado.processamento().enviados());
        assertEquals(1, resultado.processamento().pendentes());
        verify(registros, times(1000)).reprocessarCanhotoVedacitPorCte(any(), eq(fonte));
    }

    @Test void interrompeDrenoEmTresErrosConsecutivos() {
        var massa = prepararMil();
        when(registros.reprocessarCanhotoVedacitPorCte(any(), eq(fonte))).thenReturn(ResultadoRegistro.ERRO);
        var resultado = service.processarClienteSftpVedacit("VEDACIT", massa, fonte, 10, 0);
        assertEquals(3, resultado.processamento().erros());
        assertEquals(3, resultado.processamento().selecionados());
        verify(repo, never()).findTecnicosSftpPorClienteENfes(any(), anyList(), any());
    }

    @Test void rodizioAvaliaDezPorTurnoEDrenaMilSemRepeticao() {
        var massa = prepararMil();
        var passagem = new EtlRepescagemService.PassagemSftp();
        passagem.limitada = true;
        Set<Long> avaliados = new HashSet<>();
        when(registros.reprocessarCanhotoVedacitPorCte(any(), eq(fonte))).thenAnswer(i -> {
            LogIntegracaoModel r = i.getArgument(0);
            assertTrue(avaliados.add(r.getId()));
            if (r.getId() == 1L) return ResultadoRegistro.PENDENTE_FOTO;
            r.setStatusCanhoto("SUCESSO"); return ResultadoRegistro.ENVIADO;
        });
        int total=0, turnos=0;
        do {
            var r = service.processarClienteSftpVedacit("VEDACIT", massa, fonte, 10, 0, passagem, 120000);
            assertTrue(r.processamento().selecionados() <= 10);
            total += r.processamento().selecionados();
            assertTrue(++turnos < 120, "A passagem deve terminar mesmo com um arquivo ausente");
        } while (passagem.temMais());
        assertEquals(1000, total);
        assertEquals(1000, avaliados.size());
    }

    @Test void inventarioSomenteRejeitadoContinuaEmPartesAteTerminar() {
        var rejeitados = new ArrayList<VedacitSftpInventory.DocumentoRejeitado>();
        for (int i=0;i<205;i++) rejeitados.add(new VedacitSftpInventory.DocumentoRejeitado("a"+i, null, null, "nome inválido"));
        var passagem = new EtlRepescagemService.PassagemSftp(); passagem.limitada=true;
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        var inventario = new VedacitSftpInventory(List.of(), rejeitados);
        service.processarClienteSftpVedacit("VEDACIT", inventario, fonte, 10, 0, passagem, 120000);
        assertTrue(passagem.temMais()); assertEquals(100, passagem.indiceInventario);
        service.processarClienteSftpVedacit("VEDACIT", inventario, fonte, 10, 0, passagem, 120000);
        assertTrue(passagem.temMais()); assertEquals(200, passagem.indiceInventario);
        service.processarClienteSftpVedacit("VEDACIT", inventario, fonte, 10, 0, passagem, 120000);
        assertFalse(passagem.temMais()); assertEquals(205, passagem.indiceInventario);
    }

    @Test void permitePrimeiroLoteSupervisionadoSemDreno() {
        var massa = prepararMil();
        ReflectionTestUtils.setField(service, "drenarFilaSftp", false);
        when(registros.reprocessarCanhotoVedacitPorCte(any(), eq(fonte))).thenReturn(ResultadoRegistro.ENVIADO);
        assertEquals(10, service.processarClienteSftpVedacit("VEDACIT", massa, fonte, 10, 0).processamento().enviados());
    }

    @Test void comprovacaoAtivaDoMesmoParLiberaFilaPreservandoDataXml() {
        var data = LocalDateTime.of(2026, 9, 1, 10, 0);
        var confirmado = LogIntegracaoModel.builder().chaveNfe(NFE).chaveCte(CTE)
                .statusDados("SUCESSO").statusCanhoto("NAO_APLICAVEL").dataProcessamentoDados(data).build();
        when(repo.findConfirmacaoVedacitAtivaPorPar(eq("VEDACIT"), eq(NFE), eq(CTE), any())).thenReturn(List.of(confirmado));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        service.processarClienteSftpVedacit("VEDACIT", new VedacitSftpInventory(List.of(documento(NFE, CTE)), List.of()), fonte, 10, 0);
        var captor = org.mockito.ArgumentCaptor.forClass(LogIntegracaoModel.class);
        verify(repo).save(captor.capture());
        assertEquals("SUCESSO", captor.getValue().getStatusDados());
        assertEquals(data, captor.getValue().getDataProcessamentoDados());
        assertEquals("PENDENTE_ENVIO", captor.getValue().getCanhotoClassificacaoOperacional());
        verifyNoInteractions(registros, fonte);
    }

    @Test void respostaSoapSemConfirmacaoFicaForaDaFilaAutomatica() {
        assertEquals(ClassificacaoOperacionalCanhotoVedacit.TIMEOUT_AMBIGUO,
                ClassificacaoOperacionalCanhotoVedacit.paraErro("Resposta SOAP sem confirmação do canhoto; conciliação necessária antes de repetir"));
    }

    @ParameterizedTest @ValueSource(strings = {"TIMEOUT_AMBIGUO", "BLOQUEADO_DESTINO"})
    void confirmacaoDeXmlNaoLiberaCanhotoRestritoNoLegado(String restricao) {
        var confirmado = LogIntegracaoModel.builder().chaveNfe(NFE).chaveCte(CTE)
                .statusDados("SUCESSO").statusCanhoto("ERRO_DESTINO")
                .canhotoClassificacaoOperacional(restricao).build();
        when(repo.findConfirmacaoVedacitAtivaPorPar(eq("VEDACIT"), eq(NFE), eq(CTE), any())).thenReturn(List.of(confirmado));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        service.processarClienteSftpVedacit("VEDACIT", new VedacitSftpInventory(List.of(documento(NFE, CTE)), List.of()), fonte, 10, 0);
        var captor = org.mockito.ArgumentCaptor.forClass(LogIntegracaoModel.class);
        verify(repo).save(captor.capture());
        assertEquals("SUCESSO", captor.getValue().getStatusDados());
        assertEquals("ERRO_DESTINO", captor.getValue().getStatusCanhoto());
        assertEquals(restricao, captor.getValue().getCanhotoClassificacaoOperacional());
        verifyNoInteractions(registros, fonte);
    }

    private VedacitSftpInventory prepararMil() {
        Map<String, LogIntegracaoModel> porCte = new LinkedHashMap<>();
        List<VedacitSftpDocument> docs = new ArrayList<>();
        for (int n = 1; n <= 1000; n++) {
            String chave = String.format("%044d", n);
            porCte.put(chave, LogIntegracaoModel.builder().id((long)n).sistemaDestino("VEDACIT").sftpCliente("VEDACIT")
                    .chaveNfe(chave).chaveCte(chave).statusDados("SUCESSO").statusCanhoto("PENDENTE_FOTO")
                    .dataProcessamento(LocalDateTime.of(2026, 9, 1, 0, 0)).build());
            docs.add(documento(chave, chave));
        }
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repo.findTopBySistemaDestinoAndSftpClienteAndChaveNfeAndChaveCteOrderByDataProcessamentoDescIdDesc(any(), any(), any(), any()))
                .thenAnswer(i -> Optional.ofNullable(porCte.get(i.getArgument(3))));
        when(repo.findById(anyLong())).thenAnswer(i -> porCte.values().stream()
                .filter(r -> r.getId().equals(i.getArgument(0))).findFirst());
        when(repo.findCandidatosSftpPorClienteENfes(any(), anyList(), any())).thenAnswer(i -> {
            List<String> chaves = i.getArgument(1); Pageable pagina = i.getArgument(2);
            assertTrue(chaves.size() <= 500);
            return porCte.values().stream().filter(r -> chaves.contains(r.getChaveNfe()))
                    .filter(r -> !"SUCESSO".equals(r.getStatusCanhoto())).limit(pagina.getPageSize()).toList();
        });
        return new VedacitSftpInventory(docs, List.of());
    }

    @Test void duasNfesNoMesmoCteMantemComprovantesIndependentes() {
        String segundaNfe = "3".repeat(44);
        var concluido = LogIntegracaoModel.builder().chaveNfe(NFE).chaveCte(CTE).statusDados("SUCESSO")
                .statusCanhoto("SUCESSO").build();
        when(repo.findTopBySistemaDestinoAndSftpClienteAndChaveNfeAndChaveCteOrderByDataProcessamentoDescIdDesc(
                "VEDACIT", "VEDACIT", NFE, CTE)).thenReturn(Optional.of(concluido));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        var resultado = service.processarClienteSftpVedacit("VEDACIT", new VedacitSftpInventory(
                List.of(documento(NFE, CTE), documento(segundaNfe, CTE)), List.of()), fonte, 10, 0);
        assertEquals(1, resultado.inventario().novos());
        var captor = org.mockito.ArgumentCaptor.forClass(LogIntegracaoModel.class);
        verify(repo).save(captor.capture());
        assertEquals(segundaNfe, captor.getValue().getChaveNfe());
        assertEquals("PENDENTE_FOTO", captor.getValue().getStatusCanhoto());
        verifyNoInteractions(registros, fonte);
    }
    private void sincronizar(boolean cliente, VedacitSftpInventory inventario) {
        if (cliente) service.processarClienteSftpVedacit("VEDACIT", inventario, fonte, 10, 0);
        else service.sincronizarInventarioSftpVedacit(inventario);
    }
    private static VedacitSftpDocument documento(String nfe, String cte) {
        return new VedacitSftpDocument(VedacitSftpDocument.Tipo.COMPROVANTE, "comprovantes/a.jpg", cte, nfe, 10L, Instant.EPOCH, null);
    }
}
