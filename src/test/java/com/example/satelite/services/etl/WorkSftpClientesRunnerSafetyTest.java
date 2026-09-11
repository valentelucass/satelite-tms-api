package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.ConfigurableApplicationContext;
import com.example.satelite.repositories.WorkSftpClientesAuditoriaRepository;
import com.example.satelite.services.origem.sftp.vedacit.*;

class WorkSftpClientesRunnerSafetyTest {
    private final VedacitSftpClientFactory factory = mock(VedacitSftpClientFactory.class);
    private final VedacitSftpClient sftp = mock(VedacitSftpClient.class);
    private final EtlRepescagemService repescagem = mock(EtlRepescagemService.class);
    private final WorkSftpClientesAuditoriaRepository auditoria = mock(WorkSftpClientesAuditoriaRepository.class);
    private final MockEnvironment env = new MockEnvironment().withProperty("VEDACIT_SFTP_RECEIPT_ONLY", "true");
    private final WorkSftpClientesRunner runner = new WorkSftpClientesRunner(factory, repescagem, env,
            mock(ConfigurableApplicationContext.class), auditoria);

    @Test
    void itemFailureMustNotBeReportedAsSuccessfulCycle() {
        setup(1);
        assertEquals(1, runner.executarCiclo());
        assertEquals(1, runner.getExitCode());
        var ciclo = cycle();
        assertEquals("OK", ciclo.conexao());
        assertEquals("FALHA", ciclo.status());
        assertEquals(2, ciclo.arquivosValidos());
        assertEquals(1, ciclo.enviados());
        assertEquals(5, ciclo.saldo());
    }

    @Test
    void inventoryFailureDoesNotMeanConnectionFailure() {
        setup(0);
        when(sftp.listarInventarioComprovantes()).thenThrow(new IllegalStateException("unit inventory"));
        assertEquals(1, runner.executarCiclo());
        assertEquals("OK", cycle().conexao());
        assertEquals("FALHA", cycle().status());
    }

    @Test void preservaResultadosParciaisQuandoBancoFalhaNoMeioDoTurno() {
        setup(0);
        when(repescagem.processarClienteSftpVedacit(any(), any(), any(), anyInt(), anyLong(), any(), anyLong()))
                .thenAnswer(i -> {
                    EtlRepescagemService.PassagemSftp passagem = i.getArgument(5);
                    passagem.totalAvaliados=2; passagem.totalEnviados=1;
                    throw new IllegalStateException("SQL com dados privados", new java.sql.SQLException("privado", "state", 1205));
                });
        assertEquals(1, runner.executarCiclo());
        var c=cycle();
        assertEquals(2, c.selecionados()); assertEquals(1, c.enviados());
        assertEquals(2, c.arquivosValidos()); assertEquals("OK", c.conexao());
        assertEquals("BANCO_FILA_PROCESSAMENTO: SQL_1205", c.motivoFalha());
    }

    @Test
    void auditFailureCannotReturnSuccessfulExitCode() {
        setup(0);
        doThrow(new IllegalStateException("unit audit unavailable")).when(auditoria).registrar(any());
        assertEquals(1, runner.executarCiclo());
    }

    @Test void migrationAusenteImpedeConectarOuEnviar() {
        doThrow(new IllegalStateException("MIGRACAO_V22_PENDENTE")).when(auditoria).validarEstrutura();
        assertEquals(2, runner.executarCiclo());
        verifyNoInteractions(factory, sftp, repescagem);
        verify(auditoria, never()).registrar(any());
    }

    @Test
    void isolatedModeIsMandatoryBeforeAnyConnection() {
        env.setProperty("VEDACIT_SFTP_RECEIPT_ONLY", "false");
        assertEquals(2, runner.executarCiclo());
        verifyNoInteractions(factory, sftp, repescagem, auditoria);
    }

    @ParameterizedTest
    @CsvSource({"WORK_SFTP_CLIENTES_MAX_ITEMS,0", "WORK_SFTP_CLIENTES_MAX_ITEMS,501",
            "WORK_SFTP_CLIENTES_INTERVAL_MS,-1", "WORK_SFTP_CLIENTES_INTERVAL_MS,60001",
            "WORK_SFTP_CLIENTES_MAX_ITEMS,invalid"})
    void rejectsInvalidLimitsBeforeConnecting(String key, String value) {
        env.setProperty(key, value);
        assertEquals(2, runner.executarCiclo());
        verifyNoInteractions(factory, sftp, repescagem, auditoria);
    }

    @Test
    void genuinelySuccessfulCycleRetainsCounts() {
        setup(0);
        assertEquals(0, runner.executarCiclo());
        assertEquals("CONCLUIDO", cycle().status());
        assertEquals(1, cycle().enviados());
        assertEquals(5, cycle().saldo());
    }

    private void setup(int erros) {
        when(factory.criarClientesHabilitados()).thenReturn(List.of(new VedacitSftpClientFactory.ClienteSftp("VEDACIT", sftp, 25)));
        var inventory = new VedacitSftpInventory(List.of(mock(VedacitSftpDocument.class), mock(VedacitSftpDocument.class)), List.of());
        when(sftp.listarInventarioComprovantes()).thenReturn(inventory);
        when(repescagem.processarClienteSftpVedacit(eq("VEDACIT"), eq(inventory), eq(sftp), eq(10), eq(1000L), any(), eq(120000L)))
                .thenReturn(new EtlRepescagemService.ResultadoClienteSftpVedacit(
                        new EtlRepescagemService.ResultadoInventarioSftpVedacit(2, 0, 0, 2),
                        new EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit(2, 1, 1 - erros, erros, 0), 5));
    }

    private WorkSftpClientesAuditoriaRepository.Ciclo cycle() {
        var capture = ArgumentCaptor.forClass(WorkSftpClientesAuditoriaRepository.Ciclo.class);
        verify(auditoria).registrar(capture.capture());
        return capture.getValue();
    }
}
