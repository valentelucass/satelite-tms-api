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
@Order(-9)
@ConditionalOnProperty(name = "vedacit.nightly-retry.run-on-start", havingValue = "true")
public class RepescagemNoturnaVedacitRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(RepescagemNoturnaVedacitRunner.class);

    private final EtlRepescagemService etlRepescagemService;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private int exitCode;

    public RepescagemNoturnaVedacitRunner(
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
            EtlRepescagemService.ResultadoRepescagemNoturnaVedacit resultado =
                    etlRepescagemService.reprocessarPendenciasTecnicasVedacit(obterLimiteItens(), obterLimiteTentativas());
            exitCode = resultado.concluidoSemErro() ? 0 : 1;
            log.info(
                    "🏁 [VEDACIT] Repescagem técnica isolada finalizada. xml={} canhotos={} enviados={} pendentes={} erros={}",
                    resultado.selecionadosXml(), resultado.selecionadosCanhoto(), resultado.enviados(),
                    resultado.pendentes(), resultado.erros()
            );
        } catch (Exception e) {
            exitCode = 1;
            log.error("💥 [VEDACIT] Falha crítica na repescagem técnica isolada.", e);
        } finally {
            int codigoSpring = SpringApplication.exit(context, () -> exitCode);
            System.exit(codigoSpring);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private int obterLimiteItens() {
        return lerInteiroPositivo("vedacit.nightly-retry.max-items", 1, 500);
    }

    private int obterLimiteTentativas() {
        return lerInteiroPositivo("vedacit.nightly-retry.max-attempts", 5, 100);
    }

    private int lerInteiroPositivo(String propriedade, int padrao, int maximo) {
        String valor = environment.getProperty(propriedade, String.valueOf(padrao));
        try {
            return Math.max(1, Math.min(maximo, Integer.parseInt(valor.trim())));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(propriedade + " deve ser inteiro positivo", e);
        }
    }
}
