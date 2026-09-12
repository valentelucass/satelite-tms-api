package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocumentSource;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

class ComprovanteEnvioIdempotenciaTest {
    private static final String NF="1".repeat(44), CTE="2".repeat(44);
    private final LogIntegracaoRepository repo=mock(LogIntegracaoRepository.class);
    private final EtlEstadoIntegracaoService estado=new EtlEstadoIntegracaoService(repo);
    private final VedacitIntegrationService destino=mock(VedacitIntegrationService.class);
    private final VedacitSftpDocumentSource fonte=mock(VedacitSftpDocumentSource.class);
    private final SftpDocumentoLockService locks=mock(SftpDocumentoLockService.class);
    private final EtlRegistroService service=new EtlRegistroService(mock(RodogarciaClient.class),
            mock(EslRequestPolicyService.class),mock(EtlResilienciaService.class),estado,
            mock(PpgIntegrationService.class),destino);
    private final AtomicBoolean confirmado=new AtomicBoolean();
    private final Map<Long,LogIntegracaoModel> registros=new ConcurrentHashMap<>();

    @BeforeEach void preparar() {
        ReflectionTestUtils.setField(service,"xmlDocumentoLockService",locks);
        var mutex=new ReentrantLock();
        when(locks.executarComLock(any(),any(),any(),any())).thenAnswer(i -> {
            mutex.lock(); try { return Optional.ofNullable(i.<Supplier<?>>getArgument(3).get()); }
            finally { mutex.unlock(); }
        });
        when(repo.buscarDataHoraServidor()).thenReturn(LocalDateTime.of(2026,9,11,12,0));
        when(repo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(registros.get(i.getArgument(0))));
        when(repo.existsConfirmacaoDuravelComprovante(NF,CTE)).thenAnswer(i -> confirmado.get());
        when(repo.existsCanhotoVedacitRetidoPorPar(NF,CTE)).thenAnswer(i -> registros.values().stream()
                .anyMatch(r -> "TIMEOUT_AMBIGUO".equals(r.getCanhotoClassificacaoOperacional())));
        when(repo.save(any())).thenAnswer(i -> {
            LogIntegracaoModel r=i.getArgument(0);
            if ("SUCESSO".equals(r.getStatusCanhoto())) confirmado.set(true);
            return r;
        });
        when(destino.processarOcorrencia(any(),isNull(),eq(true),eq(false),eq(fonte)))
                .thenReturn(ResultadoIntegracao.vedacitConcluido("SUCESSO","SUCESSO"));
    }

    private LogIntegracaoModel pendente(long id) {
        var r=LogIntegracaoModel.builder().id(id).sistemaDestino("VEDACIT").sftpCliente("CLIENTE_"+id)
                .chaveNfe(NF).chaveCte(CTE).status("PARCIAL").statusDados("SUCESSO")
                .dataProcessamentoDados(LocalDateTime.of(2026,8,20,12,0)).statusCanhoto("PENDENTE_FOTO")
                .canhotoClassificacaoOperacional("PENDENTE_ENVIO").tentativasCanhoto(0).build();
        registros.put(id,r); return r;
    }

    @Test void duzentasDisputasEntrePerfisProduzemUmEnvio() throws Exception {
        var a=pendente(1); var b=pendente(2);
        var executor=Executors.newFixedThreadPool(24);
        try {
            var tarefas=new ArrayList<Callable<ResultadoRegistro>>();
            for(int i=0;i<200;i++) { final var r=i%2==0?a:b; tarefas.add(() -> service.reprocessarCanhotoVedacitPorCte(r,fonte)); }
            long enviados=0;
            for(var future:executor.invokeAll(tarefas,20,TimeUnit.SECONDS))
                if(future.get()==ResultadoRegistro.ENVIADO) enviados++;
            assertEquals(1,enviados);
            verify(destino,times(1)).processarOcorrencia(any(),isNull(),eq(true),eq(false),eq(fonte));
            assertEquals(1,registros.values().stream().mapToInt(r -> r.getTentativasCanhoto()).sum());
        } finally { executor.shutdownNow(); }
    }

    @Test void quedaEntreChamadaEGravacaoMantemParRetidoInclusiveEmOutroPerfil() {
        var a=pendente(1); var b=pendente(2);
        when(destino.processarOcorrencia(any(),isNull(),eq(true),eq(false),eq(fonte)))
                .thenThrow(new AssertionError("queda simulada do processo"));
        assertThrows(AssertionError.class,() -> service.reprocessarCanhotoVedacitPorCte(a,fonte));
        assertEquals("EM_PROCESSAMENTO",a.getStatusCanhoto());
        assertEquals("TIMEOUT_AMBIGUO",a.getCanhotoClassificacaoOperacional());
        assertEquals(ResultadoRegistro.RETIDO,service.reprocessarCanhotoVedacitPorCte(b,fonte));
        verify(destino,times(1)).processarOcorrencia(any(),isNull(),eq(true),eq(false),eq(fonte));
    }

    @Test void confirmacaoDuravelImpedeEnvioMesmoComAuditoriaPendente() {
        var r=pendente(1); confirmado.set(true);
        assertEquals(ResultadoRegistro.JA_PROCESSADO,service.reprocessarCanhotoVedacitPorCte(r,fonte));
        verifyNoInteractions(destino);
        assertNull(r.getDataProcessamentoCanhoto());
    }

    @Test void falhaAoPersistirInicioNaoChamaDestino() {
        var r=pendente(1);
        doThrow(new IllegalStateException("falha local de auditoria")).when(repo).save(any());
        assertThrows(IllegalStateException.class,() -> service.reprocessarCanhotoVedacitPorCte(r,fonte));
        verifyNoInteractions(destino);
    }
}
