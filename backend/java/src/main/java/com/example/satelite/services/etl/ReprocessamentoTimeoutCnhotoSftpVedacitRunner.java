package com.example.satelite.services.etl;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Reenvia somente timeouts ambíguos de canhoto, exclusivamente pelo SFTP. */
@Component
@Order(-10)
@ConditionalOnProperty(name = "vedacit.sftp-timeout-retry.enabled", havingValue = "true")
public class ReprocessamentoTimeoutCnhotoSftpVedacitRunner implements CommandLineRunner, ExitCodeGenerator {

    private final EtlRepescagemService etlRepescagemService;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private int exitCode;

    public ReprocessamentoTimeoutCnhotoSftpVedacitRunner(
            EtlRepescagemService etlRepescagemService,
            Environment environment,
            ConfigurableApplicationContext context
    ) {
        this.etlRepescagemService = etlRepescagemService;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        try {
            exigirSftpExclusivo();
            // Uma autorização RETENTAR corresponde sempre a uma única NF-e.
            // O script pode informar a propriedade, mas nunca amplia este limite.
            int limite = 1;
            int tentativas = inteiro("vedacit.sftp-timeout-retry.max-attempts", 2, 5);
            long pausaMs = inteiro("vedacit.sftp-timeout-retry.interval-ms", 300000, 900000);
            EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit resultado =
                    etlRepescagemService.reprocessarTimeoutsAmbiguosSftpVedacit(limite, tentativas, pausaMs);
            exitCode = resultado.concluidoSemErro() ? 0 : 1;
        } catch (Exception e) {
            exitCode = 1;
            throw e;
        } finally {
            int codigoSpring = SpringApplication.exit(context, () -> exitCode);
            System.exit(codigoSpring);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private void exigirSftpExclusivo() {
        if (!environment.getProperty("SFTP_RODOGARCIA_ENABLED", Boolean.class, false)
                || !environment.getProperty("VEDACIT_SFTP_RECEIPT_ONLY", Boolean.class, false)
                || !environment.getProperty("VEDACIT_SFTP_RECONCILIATION_ENABLED", Boolean.class, false)) {
            throw new IllegalStateException("Retentativa de timeout exige SFTP exclusivo e reconciliação habilitados");
        }
    }

    private int inteiro(String propriedade, int padrao, int maximo) {
        String valor = environment.getProperty(propriedade, String.valueOf(padrao));
        try {
            int numero = Integer.parseInt(valor.trim());
            if (numero < 1 || numero > maximo) {
                throw new IllegalArgumentException(propriedade + " deve estar entre 1 e " + maximo);
            }
            return numero;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(propriedade + " deve ser inteiro positivo", e);
        }
    }
}
