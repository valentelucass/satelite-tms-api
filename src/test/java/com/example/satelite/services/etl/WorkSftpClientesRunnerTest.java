package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import com.example.satelite.repositories.WorkSftpClientesAuditoriaRepository;
import com.example.satelite.services.etl.EtlRepescagemService.ResultadoClienteSftpVedacit;
import com.example.satelite.services.etl.EtlRepescagemService.ResultadoInventarioSftpVedacit;
import com.example.satelite.services.etl.EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpClient;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpClientFactory;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpInventory;

class WorkSftpClientesRunnerTest {

    @Test
    void deveContinuarNoSegundoClienteQuandoOPrimeiroFalha() {
        VedacitSftpClientFactory factory = mock(VedacitSftpClientFactory.class);
        VedacitSftpClient falho = mock(VedacitSftpClient.class);
        VedacitSftpClient saudavel = mock(VedacitSftpClient.class);
        EtlRepescagemService repescagem = mock(EtlRepescagemService.class);
        WorkSftpClientesAuditoriaRepository auditoria = mock(WorkSftpClientesAuditoriaRepository.class);
        Environment environment = ambienteExclusivo();
        VedacitSftpInventory inventario = new VedacitSftpInventory(List.of(), List.of());
        ResultadoClienteSftpVedacit resultado = new ResultadoClienteSftpVedacit(
                new ResultadoInventarioSftpVedacit(0, 0, 0, 0),
                new ResultadoReprocessamentoCanhotoVedacit(0, 0, 0, 0, 0), 0);

        doThrow(new IllegalStateException("conexao indisponivel")).when(falho).verificarDisponibilidade();
        when(saudavel.listarInventarioComprovantes()).thenReturn(inventario);
        when(factory.criarClientesHabilitados()).thenReturn(List.of(
                new VedacitSftpClientFactory.ClienteSftp("FALHO", falho),
                new VedacitSftpClientFactory.ClienteSftp("VEDACIT", saudavel)
        ));
        when(repescagem.processarClienteSftpVedacit(eq("VEDACIT"), eq(inventario), eq(saudavel), eq(100), eq(1000L)))
                .thenReturn(resultado);
        when(repescagem.contarClassificacaoCanhotoVedacit(any(), any())).thenReturn(0L);

        int codigo = new WorkSftpClientesRunner(factory, repescagem, environment,
                mock(ConfigurableApplicationContext.class), auditoria).executarCiclo();

        assertEquals(1, codigo);
        verify(saudavel).verificarDisponibilidade();
        verify(repescagem).processarClienteSftpVedacit("VEDACIT", inventario, saudavel, 100, 1000L);
        verify(auditoria, org.mockito.Mockito.times(2)).registrar(any());
    }

    private Environment ambienteExclusivo() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("VEDACIT_SFTP_RECEIPT_ONLY", Boolean.class, false)).thenReturn(true);
        when(environment.getProperty(any(), any(String.class))).thenAnswer(invocacao -> invocacao.getArgument(1));
        return environment;
    }
}
