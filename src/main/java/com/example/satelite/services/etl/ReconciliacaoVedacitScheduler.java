package com.example.satelite.services.etl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.example.satelite.repositories.ReconciliacaoVedacitRepository;

/** Supervisor residente: detecta a janela a cada minuto, inclusive apos reinicio no meio da madrugada. */
@Component
@ConditionalOnProperty(name="VEDACIT_RECONCILIACAO_ENABLED",havingValue="true")
public class ReconciliacaoVedacitScheduler {
    private static final Logger log=LoggerFactory.getLogger(ReconciliacaoVedacitScheduler.class);
    private final ReconciliacaoVedacitRepository repository;
    private final ReconciliacaoVedacitService service;
    private final SftpDocumentoLockService locks;
    private final ReconciliacaoVedacitRelatorioService relatorio;
    private final Clock clock;
    private final LocalTime inicio,fim;
    @Autowired public ReconciliacaoVedacitScheduler(ReconciliacaoVedacitRepository repository,ReconciliacaoVedacitService service,
            SftpDocumentoLockService locks,ReconciliacaoVedacitRelatorioService relatorio,
            @Value("${VEDACIT_RECONCILIACAO_START:02:00}") String inicio,
            @Value("${VEDACIT_RECONCILIACAO_END:06:00}") String fim,
            @Value("${APP_TIME_ZONE:America/Sao_Paulo}") String zona) {
        this(repository,service,locks,relatorio,Clock.system(ZoneId.of(zona)),LocalTime.parse(inicio),LocalTime.parse(fim));
    }
    ReconciliacaoVedacitScheduler(ReconciliacaoVedacitRepository repository,ReconciliacaoVedacitService service,
            SftpDocumentoLockService locks,ReconciliacaoVedacitRelatorioService relatorio,Clock clock,LocalTime inicio,LocalTime fim) {
        if(!inicio.isBefore(fim)) throw new IllegalArgumentException("Janela noturna exige inicio antes do fim no mesmo dia");
        this.repository=repository;this.service=service;this.locks=locks;this.relatorio=relatorio;
        this.clock=clock;this.inicio=inicio;this.fim=fim;
    }
    @Scheduled(fixedDelayString="${VEDACIT_RECONCILIACAO_POLL_MS:60000}",initialDelayString="${VEDACIT_RECONCILIACAO_INITIAL_DELAY_MS:1000}")
    public void verificarJanela() {
        LocalDateTime agora=LocalDateTime.now(clock);
        if(!dentroDaJanela(agora.toLocalTime(),inicio,fim)) return;
        try {
            locks.executarRevisaoNoturna(()->{
                repository.validarEstrutura();
                repository.abrirOuRetomar(agora.toLocalDate(),agora).ifPresent(e->{
                    boolean completa=false;
                    try {
                        completa=service.processarPagina(e,()->LocalDateTime.now(clock),agora.toLocalDate().atTime(fim),
                                agora.toLocalDate().plusDays(1).atTime(inicio));
                        log.info("[RECONCILIACAO-VEDACIT] execucao={} dia={} varredura={}",e.id(),e.dia(),completa?"CONCLUIDA":"EM_ANDAMENTO");
                    } catch(RuntimeException ex) {
                        repository.falha(e.id(),LocalDateTime.now(clock));
                        log.error("[RECONCILIACAO-VEDACIT] execucao={} interrompida; checkpoint preservado",e.id());
                    }
                    try { relatorio.escrever(e,repository.resumo(e.id()),completa,LocalDateTime.now(clock)); }
                    catch(RuntimeException ex) { log.error("[RECONCILIACAO-VEDACIT] execucao={} relatorio local indisponivel; auditoria permanece no banco",e.id()); }
                });
                return true;
            });
        } catch(RuntimeException ex) {
            log.error("[RECONCILIACAO-VEDACIT] Supervisor sem acesso a estrutura/lock; nova verificacao no proximo intervalo");
        }
    }
    static boolean dentroDaJanela(LocalTime agora,LocalTime inicio,LocalTime fim) {
        return !agora.isBefore(inicio) && agora.isBefore(fim);
    }
    @PostConstruct public void validarInicializacao() {
        repository.validarEstrutura();
        log.info("[RECONCILIACAO-VEDACIT] Supervisor residente pronto: janela diaria {} ate {}, fuso {}. Fora da janela aguarda sem consultar fontes.",
                inicio,fim,clock.getZone());
    }
}
