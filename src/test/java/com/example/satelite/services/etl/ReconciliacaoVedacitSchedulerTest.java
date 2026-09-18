package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import com.example.satelite.dto.auditoria.ReconciliacaoVedacitDTO.Execucao;
import com.example.satelite.repositories.ReconciliacaoVedacitRepository;

class ReconciliacaoVedacitSchedulerTest {
    final ReconciliacaoVedacitRepository repo=mock(ReconciliacaoVedacitRepository.class);
    final ReconciliacaoVedacitService service=mock(ReconciliacaoVedacitService.class);
    final SftpDocumentoLockService locks=mock(SftpDocumentoLockService.class);
    final ReconciliacaoVedacitRelatorioService report=mock(ReconciliacaoVedacitRelatorioService.class);
    final LocalDate dia=LocalDate.of(2026,9,13);
    ReconciliacaoVedacitScheduler scheduler(int hora,int minuto) {
        var zona=ZoneId.of("America/Sao_Paulo");
        return new ReconciliacaoVedacitScheduler(repo,service,locks,report,
                Clock.fixed(dia.atTime(hora,minuto).atZone(zona).toInstant(),zona),LocalTime.of(2,0),LocalTime.of(6,0));
    }
    @Test void limitesDaJanelaSaoInclusivoAsDuasEExclusivoAsSeis() {
        assertFalse(ReconciliacaoVedacitScheduler.dentroDaJanela(LocalTime.of(1,59),LocalTime.of(2,0),LocalTime.of(6,0)));
        assertTrue(ReconciliacaoVedacitScheduler.dentroDaJanela(LocalTime.of(2,0),LocalTime.of(2,0),LocalTime.of(6,0)));
        assertTrue(ReconciliacaoVedacitScheduler.dentroDaJanela(LocalTime.of(5,59),LocalTime.of(2,0),LocalTime.of(6,0)));
        assertFalse(ReconciliacaoVedacitScheduler.dentroDaJanela(LocalTime.of(6,0),LocalTime.of(2,0),LocalTime.of(6,0)));
    }
    @Test void foraDaMadrugadaNaoConsultaBancoOuDestinos() {
        scheduler(18,0).verificarJanela();scheduler(6,0).verificarJanela();
        verifyNoInteractions(repo,service,locks,report);
    }
    @Test void reinicioAsTresRetomaCheckpointSemEsperarOutroDia() {
        var e=new Execucao(7,dia,400,900,"EM_EXECUCAO",null,null,null,null,null,null);
        when(locks.executarRevisaoNoturna(any())).thenAnswer(i->Optional.ofNullable(((Supplier<?>)i.getArgument(0)).get()));
        when(repo.abrirOuRetomar(eq(dia),any())).thenReturn(Optional.of(e));
        when(repo.resumo(7)).thenReturn(List.of());
        scheduler(3,0).verificarJanela();
        verify(service).processarPagina(eq(e),any(),eq(dia.atTime(6,0)),eq(dia.plusDays(1).atTime(2,0)));
    }
    @Test void segundaInstanciaNaoAdquireLockNemFazEnvios() {
        when(locks.executarRevisaoNoturna(any())).thenReturn(Optional.empty());
        scheduler(2,0).verificarJanela();verifyNoInteractions(repo,service,report);
    }
    @Test void noiteJaConcluidaNaoAbreSegundaVarredura() {
        when(locks.executarRevisaoNoturna(any())).thenAnswer(i->Optional.ofNullable(((Supplier<?>)i.getArgument(0)).get()));
        when(repo.abrirOuRetomar(eq(dia),any())).thenReturn(Optional.empty());
        scheduler(4,0).verificarJanela();verifyNoInteractions(service,report);
    }
    @Test void falhaDeRelatorioNaoMarcaBancoConcluidoComoFalha() {
        var e=new Execucao(7,dia,400,900,"EM_EXECUCAO",null,null,null,null,null,null);
        when(locks.executarRevisaoNoturna(any())).thenAnswer(i->Optional.ofNullable(((Supplier<?>)i.getArgument(0)).get()));
        when(repo.abrirOuRetomar(eq(dia),any())).thenReturn(Optional.of(e));
        when(repo.resumo(7)).thenReturn(List.of());
        when(service.processarPagina(eq(e),any(),any(),any())).thenReturn(true);
        doThrow(new IllegalStateException()).when(report).escrever(eq(e),anyList(),eq(true),any());
        scheduler(3,0).verificarJanela();verify(repo,never()).falha(anyLong(),any());
    }
}
