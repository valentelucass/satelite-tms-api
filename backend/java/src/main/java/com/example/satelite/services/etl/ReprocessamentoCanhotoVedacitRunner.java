package com.example.satelite.services.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Order(-10)
@ConditionalOnProperty(name = "vedacit.receipt-retry.enabled", havingValue = "true")
public class ReprocessamentoCanhotoVedacitRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReprocessamentoCanhotoVedacitRunner.class);
    private static final int LIMITE_PADRAO_SEGURO = 1;

    private final EtlRepescagemService etlRepescagemService;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private int exitCode;

    public ReprocessamentoCanhotoVedacitRunner(
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
            int limite = obterLimiteSeguro();
            EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit resultado =
                    etlRepescagemService.reprocessarCanhotosVedacit(limite);
            exitCode = resultado.concluidoSemErro() ? 0 : 1;
            log.info(
                    "🏁 [VEDACIT] Reprocessamento isolado finalizado. selecionados={} enviados={} pendentes={} erros={}",
                    resultado.selecionados(),
                    resultado.enviados(),
                    resultado.pendentes(),
                    resultado.erros()
            );
        } catch (Exception e) {
            exitCode = 1;
            log.error("💥 [VEDACIT] Falha crítica no reprocessamento isolado de canhotos.", e);
        } finally {
            int codigoSpring = SpringApplication.exit(context, () -> exitCode);
            System.exit(codigoSpring);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private int obterLimiteSeguro() {
        String valor = environment.getProperty("vedacit.receipt-retry.max-items", String.valueOf(LIMITE_PADRAO_SEGURO));
        try {
            return Math.max(1, Integer.parseInt(valor.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("vedacit.receipt-retry.max-items deve ser inteiro positivo", e);
        }
    }
}
