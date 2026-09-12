package com.example.satelite.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="VEDACIT_RECONCILIACAO_ENABLED",havingValue="true")
public class ReconciliacaoVedacitConfig {
    private final Environment env;
    public ReconciliacaoVedacitConfig(Environment env) { this.env=env; }
    @PostConstruct public void validarIsolamento() {
        for(String flag:new String[]{"APP_DASHBOARD_API_ONLY","APP_SCHEDULER_ENABLED","APP_CICLO_UNICO",
                "APP_NIGHTLY_RETRY_ENABLED","APP_ETL_REPESCAGEM_ENABLED","work.sftp-clientes.enabled","ciclo_unico",
                "retroactive.enabled","RETROACTIVE_ENABLED","vedacit.nightly-retry.run-on-start",
                "vedacit.receipt-retry.enabled","vedacit.sftp-receipt-batch.enabled","vedacit.sftp-legacy-retry.enabled",
                "vedacit.sftp-timeout-retry.enabled","vedacit.recovery.enabled","vedacit.sftp-inventory.enabled",
                "vedacit.sftp-receipt-reconciliation-preview.enabled"}) {
            if(env.getProperty(flag,Boolean.class,false)) throw new IllegalStateException("RECONCILIACAO_EXIGE_PROCESSO_EXCLUSIVO: "+flag);
        }
        if(!env.getProperty("VEDACIT_SFTP_RECEIPT_ONLY",Boolean.class,false))
            throw new IllegalStateException("RECONCILIACAO_EXIGE_COMPROVANTE_SFTP_EXCLUSIVO");
    }
}
