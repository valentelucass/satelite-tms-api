package com.example.satelite.services.reconciliacao;

import java.time.Clock;
import java.time.LocalDateTime;
import com.example.satelite.policy.ReconciliacaoLocalPolicy;
import com.example.satelite.repositories.ReconciliacaoLocalRepository;
import com.example.satelite.services.etl.SftpDocumentoLockService;

/** PPG/SELIA/SUPPORTE: conferência local sem usar clientes de envio como consulta. */
public final class ReconciliacaoLocalAdapter implements ReconciliacaoCliente {
    private final String cliente;
    private final ReconciliacaoLocalRepository repository;
    private final SftpDocumentoLockService locks;
    private final int tamanho;
    public ReconciliacaoLocalAdapter(String cliente, ReconciliacaoLocalRepository repository,
            SftpDocumentoLockService locks, int tamanho) {
        this.cliente=cliente; this.repository=repository; this.locks=locks;
        if(tamanho<1 || tamanho>200) throw new IllegalArgumentException("PAGINA_RECONCILIACAO_INVALIDA");
        this.tamanho=tamanho;
    }
    @Override public String cliente() { return cliente; }
    @Override public void validarEstrutura() { repository.validarEstrutura(); }
    @Override public void processarPassagem(Clock relogio, LocalDateTime fimJanela, LocalDateTime proximaRevisao) {
        locks.executarRevisaoCliente(cliente, () -> {
            LocalDateTime agora=LocalDateTime.now(relogio);
            if(!agora.isBefore(fimJanela) || Thread.currentThread().isInterrupted()) return false;
            repository.abrirOuRetomar(cliente,agora.toLocalDate(),agora).ifPresent(e -> {
                var pagina=repository.pagina(e,tamanho);
                if(pagina.isEmpty()) { repository.concluir(e,LocalDateTime.now(relogio)); return; }
                for(var c:pagina) {
                    if(Thread.currentThread().isInterrupted() || !LocalDateTime.now(relogio).isBefore(fimJanela)) break;
                    repository.registrar(e,c,ReconciliacaoLocalPolicy.revisar(c),LocalDateTime.now(relogio),proximaRevisao);
                }
            });
            return true;
        });
    }
}
