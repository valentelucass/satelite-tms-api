package com.example.satelite.services.reconciliacao;

import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.example.satelite.repositories.ReconciliacaoVedacitRepository;
import com.example.satelite.services.etl.ReconciliacaoVedacitService;
import com.example.satelite.services.etl.ReconciliacaoVedacitRelatorioService;
import com.example.satelite.services.etl.SftpDocumentoLockService;

/** Preserva tabelas, checkpoint, locks e regras históricas da Vedacit. */
@Component
public class ReconciliacaoVedacitAdapter implements ReconciliacaoCliente {
    private static final Logger log = LoggerFactory.getLogger(ReconciliacaoVedacitAdapter.class);
    private final ReconciliacaoVedacitRepository repository;
    private final ReconciliacaoVedacitService service;
    private final SftpDocumentoLockService locks;
    private final ReconciliacaoVedacitRelatorioService relatorio;

    public ReconciliacaoVedacitAdapter(ReconciliacaoVedacitRepository repository, ReconciliacaoVedacitService service,
            SftpDocumentoLockService locks, ReconciliacaoVedacitRelatorioService relatorio) {
        this.repository = repository; this.service = service; this.locks = locks; this.relatorio = relatorio;
    }
    @Override public String cliente() { return "VEDACIT"; }
    @Override public void validarEstrutura() { repository.validarEstrutura(); }

    @Override public void processarPassagem(Clock relogio, LocalDateTime fimJanela, LocalDateTime proximaRevisao) {
        locks.executarRevisaoNoturna(() -> {
            LocalDateTime agora = LocalDateTime.now(relogio);
            if (!agora.isBefore(fimJanela) || Thread.currentThread().isInterrupted()) return false;
            repository.abrirOuRetomar(agora.toLocalDate(), agora).ifPresent(e -> {
                boolean completa = false;
                try {
                    completa = service.processarPagina(e, () -> LocalDateTime.now(relogio), fimJanela, proximaRevisao);
                    log.info("[RECONCILIACAO-CLIENTES] cliente=VEDACIT execucao={} dia={} varredura={}",
                            e.id(), e.dia(), completa ? "CONCLUIDA" : "EM_ANDAMENTO");
                } catch (RuntimeException ex) {
                    repository.falha(e.id(), LocalDateTime.now(relogio));
                    log.error("[RECONCILIACAO-CLIENTES] cliente=VEDACIT execucao={} interrompida; checkpoint preservado", e.id());
                }
                try { relatorio.escrever(e, repository.resumo(e.id()), completa, LocalDateTime.now(relogio)); }
                catch (RuntimeException ex) {
                    log.error("[RECONCILIACAO-CLIENTES] cliente=VEDACIT execucao={} relatório local indisponível; auditoria preservada", e.id());
                }
            });
            return true;
        });
    }
}
