package com.example.satelite.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import com.example.satelite.repositories.ReconciliacaoLocalRepository;
import com.example.satelite.services.etl.SftpDocumentoLockService;
import com.example.satelite.services.reconciliacao.ReconciliacaoCliente;
import com.example.satelite.services.reconciliacao.ReconciliacaoLocalAdapter;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="APP_RECONCILIACAO_ENABLED",havingValue="true")
public class ReconciliacaoClientesConfig {
    private final Environment env;
    public ReconciliacaoClientesConfig(Environment env) { this.env=env; }
    @Bean public ReconciliacaoCliente reconciliacaoPpg(ReconciliacaoLocalRepository repo,SftpDocumentoLockService locks) {
        return local("PPG",repo,locks);
    }
    @Bean public ReconciliacaoCliente reconciliacaoSelia(ReconciliacaoLocalRepository repo,SftpDocumentoLockService locks) {
        return local("SELIA",repo,locks);
    }
    @Bean public ReconciliacaoCliente reconciliacaoSupporte(ReconciliacaoLocalRepository repo,SftpDocumentoLockService locks) {
        return local("SUPPORTE",repo,locks);
    }
    private ReconciliacaoCliente local(String cliente,ReconciliacaoLocalRepository repo,SftpDocumentoLockService locks) {
        return new ReconciliacaoLocalAdapter(cliente,repo,locks,env.getProperty(cliente+"_RECONCILIACAO_PAGE_SIZE",Integer.class,100));
    }
    @PostConstruct public void validarIsolamento() {
        validarIsolamento(env);
    }

    static void validarIsolamento(Environment env) {
        for(String flag:new String[]{"APP_DASHBOARD_API_ONLY","APP_SCHEDULER_ENABLED","APP_CICLO_UNICO",
                "APP_NIGHTLY_RETRY_ENABLED","APP_ETL_REPESCAGEM_ENABLED","work.sftp-clientes.enabled","ciclo_unico",
                "VEDACIT_RECONCILIACAO_ENABLED",
                "retroactive.enabled","RETROACTIVE_ENABLED","vedacit.nightly-retry.run-on-start",
                "vedacit.receipt-retry.enabled","vedacit.sftp-receipt-batch.enabled","vedacit.sftp-legacy-retry.enabled",
                "vedacit.sftp-timeout-retry.enabled","vedacit.recovery.enabled","vedacit.sftp-inventory.enabled",
                "vedacit.sftp-receipt-reconciliation-preview.enabled"}) {
            if(env.getProperty(flag,Boolean.class,false)) throw new IllegalStateException("RECONCILIACAO_EXIGE_PROCESSO_EXCLUSIVO: "+flag);
        }
        if(java.util.Arrays.stream(env.getProperty("RECONCILIACAO_CLIENTES","VEDACIT").split(","))
                .anyMatch(c -> "VEDACIT".equalsIgnoreCase(c.trim()))
                && !env.getProperty("VEDACIT_SFTP_RECEIPT_ONLY",Boolean.class,false))
            throw new IllegalStateException("RECONCILIACAO_EXIGE_COMPROVANTE_SFTP_EXCLUSIVO");
    }
}
