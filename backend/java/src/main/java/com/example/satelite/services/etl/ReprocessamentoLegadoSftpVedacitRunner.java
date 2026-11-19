package com.example.satelite.services.etl;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;

/** Runner separado: só é habilitado pelo script após confirmação humana. */
@Component
@ConditionalOnProperty(name = "vedacit.sftp-legacy-retry.enabled", havingValue = "true")
public class ReprocessamentoLegadoSftpVedacitRunner implements CommandLineRunner {
    private final EtlRepescagemService service;
    private final Environment environment;
    ReprocessamentoLegadoSftpVedacitRunner(EtlRepescagemService service, Environment environment) { this.service = service; this.environment = environment; }
    @Override public void run(String... args) {
        if (!environment.getProperty("VEDACIT_SFTP_RECONCILIATION_ENABLED", Boolean.class, false)
                || !environment.getProperty("VEDACIT_SFTP_RECEIPT_ONLY", Boolean.class, false))
            throw new IllegalStateException("Legado SFTP exige reconciliação e fonte SFTP exclusiva");
        service.reprocessarErrosLegadosSftpVedacit(environment.getProperty("vedacit.sftp-legacy-retry.max-items", Integer.class, 1));
    }
}
