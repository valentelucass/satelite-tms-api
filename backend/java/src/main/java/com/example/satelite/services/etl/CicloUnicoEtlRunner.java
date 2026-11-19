package com.example.satelite.services.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class CicloUnicoEtlRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CicloUnicoEtlRunner.class);

    private final OrquestradorEtlService orquestradorEtlService;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private int exitCode = OrquestradorEtlService.CODIGO_SAIDA_SUCESSO;

    public CicloUnicoEtlRunner(
            OrquestradorEtlService orquestradorEtlService,
            Environment environment,
            ConfigurableApplicationContext context
    ) {
        this.orquestradorEtlService = orquestradorEtlService;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        if (CicloRetroativoEtlRunner.retroativoAtivo(environment)) {
            return;
        }

        if (!cicloUnicoAtivo(environment)) {
            return;
        }

        try {
            log.info("🏁 Modo ciclo único ativo. Executando ETL uma única vez...");
            OrquestradorEtlService.ResultadoCiclo resultado = orquestradorEtlService.executarFluxosComResultado();
            exitCode = resultado.codigoSaida();
            log.info("🏁 Ciclo único finalizado com código de saída {}.", exitCode);
        } catch (Exception ex) {
            exitCode = OrquestradorEtlService.CODIGO_SAIDA_ERRO_CRITICO;
            log.error("💥 Falha crítica durante o ciclo único. Encerrando com código {}.", exitCode, ex);
        } finally {
            encerrarAplicacaoEmCicloUnico();
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    public static boolean cicloUnicoAtivo(Environment environment) {
        return propriedadeVerdadeira(environment, "APP_CICLO_UNICO")
                || propriedadeVerdadeira(environment, "ciclo_unico");
    }

    private void encerrarAplicacaoEmCicloUnico() {
        if (!cicloUnicoAtivo(environment)) {
            return;
        }

        log.info("🏁 Encerrando aplicação após ciclo único.");
        int codigoSpring = SpringApplication.exit(context);
        System.exit(codigoSpring);
    }

    private static boolean propriedadeVerdadeira(Environment environment, String nome) {
        String valor = environment.getProperty(nome);
        return valor != null && Boolean.parseBoolean(valor.trim());
    }
}
