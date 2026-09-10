package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpClient;

class ReprocessamentoCanhotoSftpVedacitRunnerTest {
    private final EtlRepescagemService service = mock(EtlRepescagemService.class);
    private final ReprocessamentoCanhotoSftpVedacitRunner runner = new ReprocessamentoCanhotoSftpVedacitRunner(
            service, mock(VedacitSftpClient.class), new MockEnvironment(), mock(ConfigurableApplicationContext.class));
    private static final List<String> INVENTORY = List.of("unit-a", "unit-b");

    @Test
    void onlyStartsTechnicalQueueAfterNormalQueueIsEmpty() {
        when(service.reprocessarCanhotosPendentesFotoSftpVedacit(eq(2),eq(0L),eq(INVENTORY),anySet()))
                .thenReturn(result(2,2,0), result(0,0,0));
        when(service.reprocessarCanhotosTecnicosSftpVedacit(1,0L,1,INVENTORY)).thenReturn(result(1,1,0));
        var actual = rounds(true,5);
        assertEquals(3, actual.enviados());
        var order = inOrder(service);
        order.verify(service,times(2)).reprocessarCanhotosPendentesFotoSftpVedacit(eq(2),eq(0L),eq(INVENTORY),anySet());
        order.verify(service).reprocessarCanhotosTecnicosSftpVedacit(1,0L,1,INVENTORY);
    }

    @Test
    void errorLimitStopsDrainWithoutStartingTechnicalQueue() {
        when(service.reprocessarCanhotosPendentesFotoSftpVedacit(eq(2),eq(0L),eq(INVENTORY),anySet())).thenReturn(result(2,0,1));
        assertEquals(1, rounds(true,5).erros());
        verify(service,times(1)).reprocessarCanhotosPendentesFotoSftpVedacit(eq(2),eq(0L),eq(INVENTORY),anySet());
        verify(service,never()).reprocessarCanhotosTecnicosSftpVedacit(anyInt(),anyLong(),anyInt(),anyList());
    }

    @Test
    void singleBatchNeverStartsExtraRound() {
        when(service.reprocessarCanhotosPendentesFotoSftpVedacit(eq(2),eq(0L),eq(INVENTORY),anySet())).thenReturn(result(2,2,0));
        assertEquals(2, rounds(false,5).enviados());
        verify(service,times(1)).reprocessarCanhotosPendentesFotoSftpVedacit(eq(2),eq(0L),eq(INVENTORY),anySet());
    }

    @Test
    void nextRoundReceivesAlreadyAttemptedDocumentsAndHonorsRoundLimit() {
        when(service.reprocessarCanhotosPendentesFotoSftpVedacit(eq(2),eq(0L),eq(INVENTORY),anySet())).thenAnswer(invocation -> {
            Set<String> attempted = invocation.getArgument(3);
            if (!attempted.contains("unit-a")) { attempted.add("unit-a"); return result(2,2,0); }
            assertTrue(attempted.contains("unit-a"));
            return result(2,2,0);
        });
        assertEquals(4, rounds(true,2).enviados());
        verify(service,times(2)).reprocessarCanhotosPendentesFotoSftpVedacit(eq(2),eq(0L),eq(INVENTORY),anySet());
        verify(service,never()).reprocessarCanhotosTecnicosSftpVedacit(anyInt(),anyLong(),anyInt(),anyList());
    }

    private EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit rounds(boolean drain,int maxRounds) {
        // Somente a lógica de rodadas: run() encerra a JVM e não é chamado pelo teste.
        return ReflectionTestUtils.invokeMethod(runner,"executarRodadas",2,0L,drain,maxRounds,0L,1,1,0L,1,INVENTORY);
    }
    private EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit result(int selected,int sent,int errors) {
        return new EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit(selected,sent,0,errors,0);
    }
}
