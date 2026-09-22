package com.example.satelite.services.reconciliacao;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ReconciliacaoClientesSchedulerTest {
    final MockEnvironment env = new MockEnvironment().withProperty("RECONCILIACAO_CLIENTES", "VEDACIT,PPG");
    final ReconciliacaoCliente vedacit = adapter("VEDACIT"), ppg = adapter("PPG");
    final ExecutorService executor = mock(ExecutorService.class);
    static ReconciliacaoCliente adapter(String cliente) {
        var a = mock(ReconciliacaoCliente.class); when(a.cliente()).thenReturn(cliente); return a;
    }
    static Clock horario(int hora, int minuto) {
        var zona = ZoneId.of("America/Sao_Paulo");
        return Clock.fixed(LocalDate.of(2026, 9, 21).atTime(hora, minuto).atZone(zona).toInstant(), zona);
    }
    ReconciliacaoClientesScheduler scheduler(int hora, int minuto) {
        return new ReconciliacaoClientesScheduler(List.of(vedacit, ppg), env, horario(hora, minuto), executor);
    }
    @Test void naoDespachaForaDaJanelaIncluiInicioExcluiFim() {
        scheduler(1,59).verificarJanelas(); scheduler(6,0).verificarJanelas();
        verifyNoInteractions(executor);
        scheduler(2,0).verificarJanelas(); verify(executor,times(2)).execute(any());
    }
    @Test void umaPassagemEmVooPorClienteSemBloquearOsDemais() {
        List<Runnable> tarefas = new ArrayList<>();
        doAnswer(i -> { tarefas.add(i.getArgument(0)); return null; }).when(executor).execute(any());
        var s = scheduler(3,0);
        s.verificarJanelas(); s.verificarJanelas();
        assertEquals(2,tarefas.size());
        tarefas.get(1).run(); // PPG avança enquanto a passagem Vedacit continua ocupada.
        s.verificarJanelas(); assertEquals(3,tarefas.size());
        verify(ppg).processarPassagem(any(),eq(LocalDateTime.of(2026,9,21,6,0)),eq(LocalDateTime.of(2026,9,22,2,0)));
        verify(vedacit,never()).processarPassagem(any(),any(),any());
    }
    @Test void falhaIsoladaLiberaNovaPassagemSemImpedirOutroCliente() {
        doAnswer(i -> { ((Runnable)i.getArgument(0)).run(); return null; }).when(executor).execute(any());
        doThrow(new IllegalStateException()).when(vedacit).processarPassagem(any(),any(),any());
        var s = scheduler(3,0); s.verificarJanelas(); s.verificarJanelas();
        verify(ppg,times(2)).processarPassagem(any(),any(),any());
        verify(vedacit,times(2)).processarPassagem(any(),any(),any());
    }
    @Test void respeitaHabilitacaoJanelaEFusoPorCliente() {
        env.withProperty("PPG_RECONCILIACAO_START","08:00").withProperty("PPG_RECONCILIACAO_END","10:00")
                .withProperty("PPG_RECONCILIACAO_TIME_ZONE","UTC");
        doAnswer(i -> { ((Runnable)i.getArgument(0)).run(); return null; }).when(executor).execute(any());
        scheduler(5,0).verificarJanelas();
        verify(ppg).processarPassagem(any(),eq(LocalDateTime.of(2026,9,21,10,0)),eq(LocalDateTime.of(2026,9,22,8,0)));
        env.withProperty("RECONCILIACAO_CLIENTES","VEDACIT");
        scheduler(5,0).validarInicializacao();
        verify(vedacit).validarEstrutura(); verify(ppg,never()).validarEstrutura();
    }
    @Test void filaQuePassouDoHorarioNaoIniciaRevisao() {
        Clock clock = mock(Clock.class); when(clock.withZone(any())).thenReturn(clock);
        when(clock.getZone()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        when(clock.instant()).thenReturn(horario(5,59).instant());
        List<Runnable> tarefas = new ArrayList<>();
        doAnswer(i -> { tarefas.add(i.getArgument(0)); return null; }).when(executor).execute(any());
        var s = new ReconciliacaoClientesScheduler(List.of(vedacit,ppg),env,clock,executor);
        s.verificarJanelas(); when(clock.instant()).thenReturn(horario(6,0).instant());
        tarefas.forEach(Runnable::run);
        verify(vedacit,never()).processarPassagem(any(),any(),any()); verify(ppg,never()).processarPassagem(any(),any(),any());
    }
    @Test void rejeitaClienteSemAdaptadorDuplicadoOuJanelaInvalida() {
        for(String clientes : List.of("INEXISTENTE","VEDACIT,VEDACIT","","VEDACIT,")) {
            env.withProperty("RECONCILIACAO_CLIENTES",clientes);
            assertThrows(IllegalArgumentException.class,() -> scheduler(3,0));
        }
        env.withProperty("RECONCILIACAO_CLIENTES","VEDACIT").withProperty("VEDACIT_RECONCILIACAO_START","06:00");
        assertThrows(IllegalArgumentException.class,() -> scheduler(3,0));
    }
    @Test void rejeicaoDoExecutorNaoPrendeClienteComoExecutando() {
        doThrow(new RejectedExecutionException()).when(executor).execute(any());
        var s=scheduler(3,0); s.verificarJanelas(); s.verificarJanelas();
        verify(executor,times(4)).execute(any());
    }
    @Test void encerraAsPassagensAoFecharContexto() throws Exception {
        scheduler(3,0).encerrar(); verify(executor).shutdownNow(); verify(executor).awaitTermination(5,TimeUnit.SECONDS);
    }
}
