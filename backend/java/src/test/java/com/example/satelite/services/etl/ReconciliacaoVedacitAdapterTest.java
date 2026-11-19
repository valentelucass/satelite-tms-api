package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import com.example.satelite.services.reconciliacao.ReconciliacaoVedacitAdapter;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import com.example.satelite.dto.auditoria.ReconciliacaoVedacitDTO.Execucao;
import com.example.satelite.repositories.ReconciliacaoVedacitRepository;

class ReconciliacaoVedacitAdapterTest {
    final ReconciliacaoVedacitRepository repo=mock(ReconciliacaoVedacitRepository.class);
    final ReconciliacaoVedacitService service=mock(ReconciliacaoVedacitService.class);
    final SftpDocumentoLockService locks=mock(SftpDocumentoLockService.class);
    final ReconciliacaoVedacitRelatorioService report=mock(ReconciliacaoVedacitRelatorioService.class);
    final LocalDate dia=LocalDate.of(2026,9,13);
    ReconciliacaoVedacitAdapter adapter() {
        return new ReconciliacaoVedacitAdapter(repo,service,locks,report);
    }
    void executar(int hora,int minuto) {
        var zona=ZoneId.of("America/Sao_Paulo");
        var clock=Clock.fixed(dia.atTime(hora,minuto).atZone(zona).toInstant(),zona);
        adapter().processarPassagem(clock,dia.atTime(6,0),dia.plusDays(1).atTime(2,0));
    }
    @Test void reinicioAsTresRetomaCheckpointSemEsperarOutroDia() {
        var e=new Execucao(7,dia,400,900,"EM_EXECUCAO",null,null,null,null,null,null);
        when(locks.executarRevisaoNoturna(any())).thenAnswer(i->Optional.ofNullable(((Supplier<?>)i.getArgument(0)).get()));
        when(repo.abrirOuRetomar(eq(dia),any())).thenReturn(Optional.of(e));
        when(repo.resumo(7)).thenReturn(List.of());
        executar(3,0);
        verify(service).processarPagina(eq(e),any(),eq(dia.atTime(6,0)),eq(dia.plusDays(1).atTime(2,0)));
    }
    @Test void segundaInstanciaNaoAdquireLockNemFazEnvios() {
        when(locks.executarRevisaoNoturna(any())).thenReturn(Optional.empty());
        executar(2,0);verifyNoInteractions(repo,service,report);
    }
    @Test void noiteJaConcluidaNaoAbreSegundaVarredura() {
        when(locks.executarRevisaoNoturna(any())).thenAnswer(i->Optional.ofNullable(((Supplier<?>)i.getArgument(0)).get()));
        when(repo.abrirOuRetomar(eq(dia),any())).thenReturn(Optional.empty());
        executar(4,0);verifyNoInteractions(service,report);
    }
    @Test void falhaDeRelatorioNaoMarcaBancoConcluidoComoFalha() {
        var e=new Execucao(7,dia,400,900,"EM_EXECUCAO",null,null,null,null,null,null);
        when(locks.executarRevisaoNoturna(any())).thenAnswer(i->Optional.ofNullable(((Supplier<?>)i.getArgument(0)).get()));
        when(repo.abrirOuRetomar(eq(dia),any())).thenReturn(Optional.of(e));
        when(repo.resumo(7)).thenReturn(List.of());
        when(service.processarPagina(eq(e),any(),any(),any())).thenReturn(true);
        doThrow(new IllegalStateException()).when(report).escrever(eq(e),anyList(),eq(true),any());
        executar(3,0);verify(repo,never()).falha(anyLong(),any());
    }
}
