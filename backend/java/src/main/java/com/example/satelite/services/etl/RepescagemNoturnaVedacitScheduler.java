package com.example.satelite.services.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "APP_NIGHTLY_RETRY_ENABLED", havingValue = "true")
public class RepescagemNoturnaVedacitScheduler {

    private static final Logger log = LoggerFactory.getLogger(RepescagemNoturnaVedacitScheduler.class);

    private final EtlRepescagemService etlRepescagemService;

    @Value("${ETL_NIGHTLY_RETRY_MAX_ITEMS:100}")
    private int limiteItens;

    @Value("${ETL_NIGHTLY_RETRY_MAX_ATTEMPTS:5}")
    private int limiteTentativas;

    public RepescagemNoturnaVedacitScheduler(EtlRepescagemService etlRepescagemService) {
        this.etlRepescagemService = etlRepescagemService;
    }

    @Scheduled(
            cron = "${ETL_NIGHTLY_RETRY_CRON:0 30 23 * * *}",
            zone = "${APP_TIME_ZONE:America/Sao_Paulo}"
    )
    public void executar() {
        try {
            etlRepescagemService.reprocessarPendenciasTecnicasVedacit(limiteItens, limiteTentativas);
        } catch (Exception e) {
            log.error("💥 [VEDACIT] Falha crítica na repescagem noturna.", e);
        }
    }
}
