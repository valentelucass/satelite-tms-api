package com.example.satelite.services.reconciliacao;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import com.example.satelite.repositories.ReconciliacaoLocalRepository;
import com.example.satelite.services.etl.SftpDocumentoLockService;
import com.example.satelite.dto.auditoria.ReconciliacaoLocalDTO.*;

class ReconciliacaoLocalAdapterTest {
    final ReconciliacaoLocalRepository repo=mock(ReconciliacaoLocalRepository.class);
    final SftpDocumentoLockService locks=mock(SftpDocumentoLockService.class);
    final ReconciliacaoLocalAdapter adapter=new ReconciliacaoLocalAdapter("PPG",repo,locks,100);
    final LocalDate dia=LocalDate.of(2026,9,21);
    final Clock clock=Clock.fixed(Instant.parse("2026-09-21T06:00:00Z"),ZoneId.of("America/Sao_Paulo"));
    final Execucao e=new Execucao(1,"PPG",dia,10,100);
    void permitirLock() {
        when(locks.executarRevisaoCliente(eq("PPG"),any())).thenAnswer(i -> Optional.ofNullable(((Supplier<?>)i.getArgument(1)).get()));
    }
    void executar() { adapter.processarPassagem(clock,dia.atTime(6,0),dia.plusDays(1).atTime(2,0)); }
    @Test void semLockNaoConsultaOuRegistra() { executar(); verifyNoInteractions(repo); }
    @Test void leUmaPaginaRegistraDiagnosticoSemMudarDadosDoDocumento() {
        permitirLock(); when(repo.abrirOuRetomar(eq("PPG"),eq(dia),any())).thenReturn(Optional.of(e));
        var candidato=new Candidato(12,false,"SUCESSO","ERRO_DESTINO",dia.atStartOfDay(),null);
        when(repo.pagina(e,100)).thenReturn(List.of(candidato));
        executar();
        verify(repo).registrar(eq(e),eq(candidato),eq(new Revisao("SUCESSO_REGISTRADO_LOCAL",
                "FALHA_REGISTRADA_SEM_CONFIRMACAO_EXTERNA","CONFERENCIA_LOCAL_SEM_REENVIO")),any(),eq(dia.plusDays(1).atTime(2,0)));
        verify(repo,never()).concluir(any(),any());
    }
    @Test void paginaVaziaConcluiSemReabrirOutroDia() {
        permitirLock(); when(repo.abrirOuRetomar(eq("PPG"),eq(dia),any())).thenReturn(Optional.of(e));
        when(repo.pagina(e,100)).thenReturn(List.of()); executar(); verify(repo).concluir(eq(e),any());
    }
    @Test void depoisDaJanelaNaoAbreExecucao() {
        permitirLock(); adapter.processarPassagem(clock,dia.atTime(3,0),dia.plusDays(1).atTime(2,0));
        verifyNoInteractions(repo);
    }
    @Test void paginaExigeLimitePequeno() {
        assertThrows(IllegalArgumentException.class,()->new ReconciliacaoLocalAdapter("PPG",repo,locks,201));
    }
}
