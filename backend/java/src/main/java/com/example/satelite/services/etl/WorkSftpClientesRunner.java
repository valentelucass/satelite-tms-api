package com.example.satelite.services.etl;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Modo avulso usado pelo cadastro SFTP legado do PM2. */
@Component
@Order(-30)
@ConditionalOnProperty(name = "work.sftp-clientes.enabled", havingValue = "true")
public class WorkSftpClientesRunner implements CommandLineRunner, ExitCodeGenerator {
    private final CicloSftpClientes ciclo;
    private final ConfigurableApplicationContext context;
    private int exitCode;

    WorkSftpClientesRunner(CicloSftpClientes ciclo, ConfigurableApplicationContext context) {
        this.ciclo = ciclo;
        this.context = context;
    }

    @Override public void run(String... args) {
        exitCode = ciclo.executarCiclo();
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    @Override public int getExitCode() { return exitCode; }
}
