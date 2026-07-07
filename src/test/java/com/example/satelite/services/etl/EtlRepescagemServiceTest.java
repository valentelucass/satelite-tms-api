package com.example.satelite.services.etl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
}
