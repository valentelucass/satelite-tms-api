package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

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
