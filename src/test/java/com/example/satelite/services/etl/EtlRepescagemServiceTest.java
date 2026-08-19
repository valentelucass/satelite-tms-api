package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocument;

class EtlRepescagemServiceTest {

    @Test
    void deveReprocessarErroParcialDeCanhotoSemJanelaEPulandoDadosVedacit() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        EtlRegistroService etlRegistroService = mock(EtlRegistroService.class);
        EtlEstadoIntegracaoService etlEstadoIntegracaoService = mock(EtlEstadoIntegracaoService.class);
        PpgIntegrationService ppgIntegrationService = mock(PpgIntegrationService.class);
        VedacitIntegrationService vedacitIntegrationService = mock(VedacitIntegrationService.class);
        EtlRepescagemService service = new EtlRepescagemService(
                repository,
                etlRegistroService,
                etlEstadoIntegracaoService,
                ppgIntegrationService,
                vedacitIntegrationService
        );
        ReflectionTestUtils.setField(service, "tokenVedacitEsl", "token-vedacit");
        ReflectionTestUtils.setField(service, "intervaloEntreRegistrosMs", 0L);

        LocalDateTime inicioCiclo = LocalDateTime.of(2026, 7, 7, 12, 0);
        LogIntegracaoModel erroParcial = LogIntegracaoModel.builder()
                .id(10L)
                .sistemaDestino("VEDACIT")
                .chaveNfe("35260560642774001209550010002155001385723840")
                .status(ResultadoIntegracao.STATUS_ERRO_DESTINO)
                .statusDados(ResultadoIntegracao.STATUS_SUCESSO)
                .statusCanhoto(ResultadoIntegracao.STATUS_ERRO_DESTINO)
                .tentativasDados(1)
                .tentativasCanhoto(2)
                .build();

        when(repository.findErrosManuaisDesde(inicioCiclo)).thenReturn(List.of());
        when(repository.findErrosParciaisCanhotoPendentesRetry()).thenReturn(List.of(erroParcial));
        when(etlEstadoIntegracaoService.statusSucesso(ResultadoIntegracao.STATUS_SUCESSO)).thenReturn(true);
        when(etlEstadoIntegracaoService.statusSucesso(ResultadoIntegracao.STATUS_ERRO_DESTINO)).thenReturn(false);
        when(etlRegistroService.reprocessarLogExistente(
                eq("VEDACIT"),
                eq("Bearer token-vedacit"),
                eq(erroParcial),
                any()
        )).thenReturn(ResultadoRegistro.ENVIADO);

        service.executarRepescagem(inicioCiclo);

        ArgumentCaptor<ProcessadorDestino> processadorCaptor = ArgumentCaptor.forClass(ProcessadorDestino.class);
        verify(repository).findErrosParciaisCanhotoPendentesRetry();
        verify(etlRegistroService).reprocessarLogExistente(
                eq("VEDACIT"),
                eq("Bearer token-vedacit"),
                eq(erroParcial),
                processadorCaptor.capture()
        );

        processadorCaptor.getValue().processar(null, null, erroParcial);

        verify(vedacitIntegrationService).processarOcorrencia(null, null, true, false);
    }

    @Test
    void deveManterSeliaEmQuarentenaSemRepescagemGenerica() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        EtlRegistroService etlRegistroService = mock(EtlRegistroService.class);
        EtlEstadoIntegracaoService etlEstadoIntegracaoService = mock(EtlEstadoIntegracaoService.class);
        PpgIntegrationService ppgIntegrationService = mock(PpgIntegrationService.class);
        VedacitIntegrationService vedacitIntegrationService = mock(VedacitIntegrationService.class);
        EtlRepescagemService service = new EtlRepescagemService(
                repository,
                etlRegistroService,
                etlEstadoIntegracaoService,
                ppgIntegrationService,
                vedacitIntegrationService
        );
        ReflectionTestUtils.setField(service, "intervaloEntreRegistrosMs", 0L);

        LocalDateTime inicioCiclo = LocalDateTime.of(2026, 8, 5, 12, 0);
        LogIntegracaoModel erroSelia = LogIntegracaoModel.builder()
                .id(11L)
                .sistemaDestino("SELIA")
                .chaveNfe("35260560642774001209550010002155001385723840")
                .status(ResultadoIntegracao.STATUS_ERRO_DESTINO)
                .tentativasDados(3)
                .tentativasCanhoto(3)
                .build();

        when(repository.findErrosManuaisDesde(inicioCiclo)).thenReturn(List.of(erroSelia));
        when(repository.findErrosParciaisCanhotoPendentesRetry()).thenReturn(List.of());

        service.executarRepescagem(inicioCiclo);

        verify(etlRegistroService, never()).reprocessarLogExistente(any(), any(), any(), any());
    }

    @Test
    void deveReprocessarSomenteCandidatoTecnicoVedacitNaRotinaNoturna() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        EtlRegistroService etlRegistroService = mock(EtlRegistroService.class);
        EtlEstadoIntegracaoService etlEstadoIntegracaoService = mock(EtlEstadoIntegracaoService.class);
        EtlRepescagemService service = new EtlRepescagemService(
                repository,
                etlRegistroService,
                etlEstadoIntegracaoService,
                mock(PpgIntegrationService.class),
                mock(VedacitIntegrationService.class)
        );
        ReflectionTestUtils.setField(service, "intervaloEntreRegistrosMs", 0L);

        LogIntegracaoModel erroXml = LogIntegracaoModel.builder()
                .id(20L)
                .sistemaDestino("VEDACIT")
                .chaveNfe("35260760642774001209550010002329831546555019")
                .chaveCte("35260760960473000758570030000521491971250456")
                .status(ResultadoIntegracao.STATUS_ERRO_DESTINO)
                .statusDados(ResultadoIntegracao.STATUS_ERRO_DESTINO)
                .tentativasDados(3)
                .build();

        when(repository.findCandidatosRepescagemNoturnaVedacitDados(eq(5), any()))
                .thenReturn(List.of(erroXml));
        when(repository.findCandidatosRepescagemNoturnaVedacitCanhoto(eq(5), any()))
                .thenReturn(List.of());
        when(etlRegistroService.reprocessarXmlCteVedacitPorChave(erroXml)).thenReturn(ResultadoRegistro.ENVIADO);

        EtlRepescagemService.ResultadoRepescagemNoturnaVedacit resultado =
                service.reprocessarPendenciasTecnicasVedacit(10, 5);

        assertEquals(1, resultado.selecionadosXml());
        assertEquals(0, resultado.selecionadosCanhoto());
        assertEquals(1, resultado.enviados());
        assertEquals(0, resultado.erros());
        verify(etlRegistroService).reprocessarXmlCteVedacitPorChave(erroXml);
        verify(etlRegistroService, never()).reprocessarCanhotoVedacitPorCte(any());
    }

    @Test
    void deveReprocessarLoteSftpSomenteDeCanhotosPendentesComCte() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        EtlRegistroService etlRegistroService = mock(EtlRegistroService.class);
        EtlEstadoIntegracaoService estadoIntegracaoService = mock(EtlEstadoIntegracaoService.class);
        EtlRepescagemService service = new EtlRepescagemService(
                repository,
                etlRegistroService,
                estadoIntegracaoService,
                mock(PpgIntegrationService.class),
                mock(VedacitIntegrationService.class)
        );
        LogIntegracaoModel pendente = LogIntegracaoModel.builder()
                .id(40L)
                .sistemaDestino("VEDACIT")
                .chaveNfe("35260860642774001209550010002365771266072428")
                .chaveCte("35260860960473000758570030000541141709521720")
                .status(ResultadoIntegracao.STATUS_PARCIAL)
                .statusDados(ResultadoIntegracao.STATUS_SUCESSO)
                .statusCanhoto(ResultadoIntegracao.STATUS_PENDENTE_FOTO)
                .build();

        when(repository.findCanhotosPendentesFotoVedacit(any())).thenReturn(List.of(pendente));
        when(etlRegistroService.reprocessarCanhotoVedacitPorCte(pendente)).thenReturn(ResultadoRegistro.ENVIADO);

        EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit resultado =
                service.reprocessarCanhotosPendentesFotoSftpVedacit(10, 0);

        assertEquals(1, resultado.selecionados());
        assertEquals(1, resultado.enviados());
        assertEquals(0, resultado.pendentes());
        assertEquals(0, resultado.erros());
        verify(repository).findCanhotosPendentesFotoVedacit(any());
        verify(repository, never()).findErrosParciaisCanhotoVedacit(any());
        verify(etlRegistroService).reprocessarCanhotoVedacitPorCte(pendente);
        assertEquals("SFTP", pendente.getCanhotoOrigem());
        verify(estadoIntegracaoService).salvar(pendente);
    }

    @Test
    void deveProcessarUmaUnicaVezCadaNfeESeguirParaProximaRodada() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        EtlRegistroService etlRegistroService = mock(EtlRegistroService.class);
        EtlEstadoIntegracaoService estadoIntegracaoService = mock(EtlEstadoIntegracaoService.class);
        EtlRepescagemService service = new EtlRepescagemService(
                repository,
                etlRegistroService,
                estadoIntegracaoService,
                mock(PpgIntegrationService.class),
                mock(VedacitIntegrationService.class)
        );
        String nfePrimeira = "35260860642774001209550010002365771266072428";
        String nfeSegunda = "35260760642774001209550010002329831546555019";
        LogIntegracaoModel primeiraAntiga = pendenciaSftp(40L, nfePrimeira, "35260860960473000758570030000541141709521720");
        LogIntegracaoModel primeiraDuplicada = pendenciaSftp(41L, nfePrimeira, "35260860960473000758570030000541141709521720");
        LogIntegracaoModel segunda = pendenciaSftp(42L, nfeSegunda, "35260760960473000758570030000521491971250456");
        Set<String> tentadas = new HashSet<>();

        when(repository.findCanhotosPendentesFotoVedacitPorNfes(eq(List.of(nfePrimeira, nfeSegunda)), any()))
                .thenReturn(List.of(primeiraAntiga, primeiraDuplicada));
        when(repository.findCanhotosPendentesFotoVedacitPorNfesExcluindoJaTentadas(
                eq(List.of(nfePrimeira, nfeSegunda)), eq(List.of(nfePrimeira)), any()
        )).thenReturn(List.of(segunda));
        when(etlRegistroService.reprocessarCanhotoVedacitPorCte(primeiraAntiga)).thenReturn(ResultadoRegistro.ENVIADO);
        when(etlRegistroService.reprocessarCanhotoVedacitPorCte(segunda)).thenReturn(ResultadoRegistro.PENDENTE_FOTO);

        var primeiraRodada = service.reprocessarCanhotosPendentesFotoSftpVedacit(
                100, 0, List.of(nfePrimeira, nfeSegunda), tentadas
        );
        var segundaRodada = service.reprocessarCanhotosPendentesFotoSftpVedacit(
                100, 0, List.of(nfePrimeira, nfeSegunda), tentadas
        );

        assertEquals(1, primeiraRodada.selecionados());
        assertEquals(1, primeiraRodada.enviados());
        assertEquals(1, segundaRodada.selecionados());
        assertEquals(1, segundaRodada.pendentes());
        assertEquals(Set.of(nfePrimeira, nfeSegunda), tentadas);
        verify(etlRegistroService, never()).reprocessarCanhotoVedacitPorCte(primeiraDuplicada);
    }

    @Test
    void deveCriarAuditoriaMinimaParaComprovanteSftpAindaDesconhecido() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        EtlEstadoIntegracaoService estado = new EtlEstadoIntegracaoService(repository);
        EtlRepescagemService service = new EtlRepescagemService(
                repository, mock(EtlRegistroService.class), estado,
                mock(PpgIntegrationService.class), mock(VedacitIntegrationService.class)
        );
        String nfe = "35260860642774001209550010002365771266072428";
        String cte = "35260860960473000758570030000541141709521720";
        var documento = new VedacitSftpDocument(
                VedacitSftpDocument.Tipo.COMPROVANTE, "comprovantes/1_" + cte + "_" + nfe + ".jpg",
                cte, nfe, 10L, java.time.Instant.now(), null
        );
        when(repository.findTopBySistemaDestinoAndChaveCteOrderByDataProcessamentoDescIdDesc("VEDACIT", cte))
                .thenReturn(java.util.Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var resultado = service.sincronizarInventarioSftpVedacit(List.of(documento));

        assertEquals(1, resultado.arquivos());
        assertEquals(1, resultado.novos());
        ArgumentCaptor<LogIntegracaoModel> captor = ArgumentCaptor.forClass(LogIntegracaoModel.class);
        verify(repository).save(captor.capture());
        assertEquals(nfe, captor.getValue().getChaveNfe());
        assertEquals(cte, captor.getValue().getChaveCte());
        assertEquals(ResultadoIntegracao.STATUS_PENDENTE_FOTO, captor.getValue().getStatusCanhoto());
        assertEquals("SFTP", captor.getValue().getCanhotoOrigem());
    }

    @Test
    void deveReprocessarSomenteErroTecnicoClassificadoAposFilaNormal() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        EtlRegistroService registroService = mock(EtlRegistroService.class);
        EtlEstadoIntegracaoService estado = mock(EtlEstadoIntegracaoService.class);
        EtlRepescagemService service = new EtlRepescagemService(
                repository, registroService, estado, mock(PpgIntegrationService.class), mock(VedacitIntegrationService.class)
        );
        String nfe = "35260860642774001209550010002365771266072428";
        LogIntegracaoModel tecnico = pendenciaSftp(70L, nfe, "35260860960473000758570030000541141709521720");
        tecnico.setStatus(ResultadoIntegracao.STATUS_ERRO_DESTINO);
        tecnico.setStatusCanhoto(ResultadoIntegracao.STATUS_ERRO_DESTINO);
        tecnico.setCanhotoClassificacaoOperacional("PENDENTE_TECNICO");
        when(repository.findCanhotosTecnicosSftpVedacitPorNfes(eq(List.of(nfe)), any())).thenReturn(List.of(tecnico));
        when(registroService.reprocessarCanhotoVedacitPorCte(tecnico)).thenReturn(ResultadoRegistro.ENVIADO);

        var resultado = service.reprocessarCanhotosTecnicosSftpVedacit(100, 0, 1, List.of(nfe));

        assertEquals(1, resultado.selecionados());
        assertEquals(1, resultado.enviados());
        verify(registroService).reprocessarCanhotoVedacitPorCte(tecnico);
    }

    @Test
    void devePausarQuarentenaTecnicaAoAtingirLimiteDeErros() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        EtlRegistroService registroService = mock(EtlRegistroService.class);
        EtlRepescagemService service = new EtlRepescagemService(
                repository, registroService, mock(EtlEstadoIntegracaoService.class),
                mock(PpgIntegrationService.class), mock(VedacitIntegrationService.class)
        );
        LogIntegracaoModel primeiro = pendenciaTecnicaSftp(71L, "35260860642774001209550010002365771266072428");
        LogIntegracaoModel segundo = pendenciaTecnicaSftp(72L, "35260760642774001209550010002329831546555019");
        when(repository.findCanhotosTecnicosSftpVedacitPorNfes(any(), any()))
                .thenReturn(List.of(primeiro, segundo));
        when(registroService.reprocessarCanhotoVedacitPorCte(primeiro)).thenReturn(ResultadoRegistro.ERRO);

        var resultado = service.reprocessarCanhotosTecnicosSftpVedacit(
                10, 0, 1, List.of(primeiro.getChaveNfe(), segundo.getChaveNfe())
        );

        assertEquals(1, resultado.selecionados());
        assertEquals(1, resultado.erros());
        verify(registroService, never()).reprocessarCanhotoVedacitPorCte(segundo);
    }

    private LogIntegracaoModel pendenciaTecnicaSftp(Long id, String chaveNfe) {
        LogIntegracaoModel registro = pendenciaSftp(
                id, chaveNfe, "35260860960473000758570030000541141709521720"
        );
        registro.setStatus(ResultadoIntegracao.STATUS_ERRO_DESTINO);
        registro.setStatusCanhoto(ResultadoIntegracao.STATUS_ERRO_DESTINO);
        registro.setCanhotoClassificacaoOperacional("PENDENTE_TECNICO");
        return registro;
    }

    private LogIntegracaoModel pendenciaSftp(Long id, String chaveNfe, String chaveCte) {
        return LogIntegracaoModel.builder()
                .id(id)
                .sistemaDestino("VEDACIT")
                .chaveNfe(chaveNfe)
                .chaveCte(chaveCte)
                .status(ResultadoIntegracao.STATUS_PARCIAL)
                .statusDados(ResultadoIntegracao.STATUS_SUCESSO)
                .statusCanhoto(ResultadoIntegracao.STATUS_PENDENTE_FOTO)
                .build();
    }

    @Test
    void deveRecuperarChaveCteDeErroHistorico401SemReenviarCanhoto() {
        LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
        EtlRegistroService etlRegistroService = mock(EtlRegistroService.class);
        EtlRepescagemService service = new EtlRepescagemService(
                repository,
                etlRegistroService,
                mock(EtlEstadoIntegracaoService.class),
                mock(PpgIntegrationService.class),
                mock(VedacitIntegrationService.class)
        );
        ReflectionTestUtils.setField(service, "intervaloEntreRegistrosMs", 0L);

        String chaveCte = "35260760960473000758570030000513821605790206";
        LogIntegracaoModel historico401 = LogIntegracaoModel.builder()
                .id(30L)
                .sistemaDestino("VEDACIT")
                .chaveNfe("35260760642774001209550010002329831546555019")
                .status(ResultadoIntegracao.STATUS_ERRO_DESTINO)
                .statusDados(ResultadoIntegracao.STATUS_ERRO_DESTINO)
                .statusCanhoto("RECEBIDO")
                .tentativasDados(4)
                .mensagemErroDados("[401 Unauthorized] during [GET] to [https://rodogarcia.eslcloud.com.br/api/ctes?key="
                        + chaveCte + "]")
                .build();

        when(repository.findCandidatosRepescagemNoturnaVedacitDados(eq(5), any()))
                .thenReturn(List.of());
        when(repository.findQuarentenaByDestino("VEDACIT")).thenReturn(List.of(historico401));
        when(repository.findCandidatosRepescagemNoturnaVedacitCanhoto(eq(5), any()))
                .thenReturn(List.of());
        when(etlRegistroService.reprocessarXmlCteVedacitPorChave(historico401))
                .thenReturn(ResultadoRegistro.ENVIADO);

        EtlRepescagemService.ResultadoRepescagemNoturnaVedacit resultado =
                service.reprocessarPendenciasTecnicasVedacit(1, 5);

        assertEquals(chaveCte, historico401.getChaveCte());
        assertEquals(1, resultado.selecionadosXml());
        assertEquals(1, resultado.enviados());
        verify(etlRegistroService).reprocessarXmlCteVedacitPorChave(historico401);
        verify(etlRegistroService, never()).reprocessarCanhotoVedacitPorCte(any());
    }
}
