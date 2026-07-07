package com.example.satelite.services.etl;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class CicloRetroativoEtlRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CicloRetroativoEtlRunner.class);
    private static final int MAX_PAGINAS_RETROATIVO_PADRAO = 10;
    private static final String PROPRIEDADE_MAX_PAGINAS_CICLO = "INTEGRATION_MAX_PAGES_PER_CYCLE";
    private static final DateTimeFormatter FORMATO_DATA_RETROATIVA = DateTimeFormatter.ISO_LOCAL_DATE;

    private final OrquestradorEtlService orquestradorEtlService;
    private final EslRequestPolicyService eslRequestPolicyService;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private int exitCode = OrquestradorEtlService.CODIGO_SAIDA_SUCESSO;

    public CicloRetroativoEtlRunner(
            OrquestradorEtlService orquestradorEtlService,
            EslRequestPolicyService eslRequestPolicyService,
            Environment environment,
            ConfigurableApplicationContext context
    ) {
        this.orquestradorEtlService = orquestradorEtlService;
        this.eslRequestPolicyService = eslRequestPolicyService;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        if (!retroativoAtivo(environment)) {
            return;
        }

        try {
            LocalDate dataInicial = obterDataObrigatoria("retroactive.start", "RETROACTIVE_START");
            LocalDate dataFinal = obterDataObrigatoria("retroactive.end", "RETROACTIVE_END");
            String destino = obterDestino();
            int maxPaginasOperacional = obterInteiro(
                    PROPRIEDADE_MAX_PAGINAS_CICLO,
                    MAX_PAGINAS_RETROATIVO_PADRAO
            );
            int maxPaginasSolicitadas = obterInteiro("retroactive.max-pages", obterInteiro(
                    "RETROACTIVE_MAX_PAGES",
                    maxPaginasOperacional
            ));
            int maxPaginas = limitarMaxPaginas(maxPaginasSolicitadas, maxPaginasOperacional);

            ExecucaoEtlRequest request = ExecucaoEtlRequest.retroativo(
                    dataInicial,
                    dataFinal,
                    destino,
                    maxPaginas
            );

            validarCooldown(destino, dataInicial, dataFinal);
            eslRequestPolicyService.registrarExtracaoPeriodo(destino, dataInicial, dataFinal);

            log.info(
                    "🏁 Modo retroativo ativo. destino={} inicio={} fim={} paginasPorLoteAntesDePausa={}",
                    destino,
                    dataInicial,
                    dataFinal,
                    maxPaginas
            );

            OrquestradorEtlService.ResultadoCiclo resultado =
                    orquestradorEtlService.executarFluxosComResultado(request);
            exitCode = resultado.codigoSaida();
            log.info("🏁 Carga retroativa finalizada com código de saída {}.", exitCode);
        } catch (Exception ex) {
            exitCode = OrquestradorEtlService.CODIGO_SAIDA_ERRO_CRITICO;
            log.error("💥 Falha crítica durante carga retroativa. Encerrando com código {}.", exitCode, ex);
        } finally {
            encerrarAplicacao();
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    public static boolean retroativoAtivo(Environment environment) {
        return propriedadeVerdadeira(environment, "retroactive.enabled")
                || propriedadeVerdadeira(environment, "RETROACTIVE_ENABLED");
    }

    private LocalDate obterDataObrigatoria(String propriedade, String propriedadeAlternativa) {
        String valor = environment.getProperty(propriedade);
        String propriedadeInformada = propriedade;
        if (valor == null || valor.isBlank()) {
            valor = environment.getProperty(propriedadeAlternativa);
            propriedadeInformada = propriedadeAlternativa;
        }

        return parseDataObrigatoria(propriedadeInformada, valor);
    }

    static LocalDate parseDataObrigatoria(String propriedade, String valor) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("Parametro obrigatorio ausente: --" + propriedade);
        }

        try {
            return LocalDate.parse(valor.trim(), FORMATO_DATA_RETROATIVA);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Parametro --" + propriedade
                            + " deve usar o formato AAAA-MM-DD (ex: 2026-05-01). Valor recebido: "
                            + valor,
                    ex
            );
        }
    }

    private String obterDestino() {
        String destino = environment.getProperty("retroactive.destino");
        if (destino == null || destino.isBlank()) {
            destino = environment.getProperty("RETROACTIVE_DESTINO", "TODOS");
        }

        return destino;
    }

    private int obterInteiro(String propriedade, int valorPadrao) {
        String valor = environment.getProperty(propriedade);
        if (valor == null || valor.isBlank()) {
            return valorPadrao;
        }

        try {
            return Integer.parseInt(valor.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "Parametro --" + propriedade + " deve ser um numero inteiro. Valor recebido: " + valor,
                    ex
            );
        }
    }

    private int limitarMaxPaginas(int maxPaginasSolicitadas, int maxPaginasOperacional) {
        int maxPaginas = Math.min(maxPaginasSolicitadas, maxPaginasOperacional);
        if (maxPaginasSolicitadas > maxPaginasOperacional) {
            log.warn(
                    "Lote retroativo solicitado ({}) excede {}={}. Usando paginasPorLoteAntesDePausa={}.",
                    maxPaginasSolicitadas,
                    PROPRIEDADE_MAX_PAGINAS_CICLO,
                    maxPaginasOperacional,
                    maxPaginas
            );
        }

        return maxPaginas;
    }

    private void validarCooldown(String destino, LocalDate dataInicial, LocalDate dataFinal) {
        Duration restante = eslRequestPolicyService.tempoRestanteParaNovaExtracaoPeriodo(
                destino,
                dataInicial,
                dataFinal
        );
        if (!restante.isZero()) {
            throw new IllegalStateException(
                    "Janela retroativa ainda em cooldown ESL. Tente novamente em " + restante.toMinutes() + " minuto(s)."
            );
        }
    }

    private void encerrarAplicacao() {
        if (!retroativoAtivo(environment)) {
            return;
        }

        log.info("🏁 Encerrando aplicação após carga retroativa.");
        int codigoSpring = SpringApplication.exit(context, () -> exitCode);
        System.exit(codigoSpring);
    }

    private static boolean propriedadeVerdadeira(Environment environment, String nome) {
        String valor = environment.getProperty(nome);
        return valor != null && Boolean.parseBoolean(valor.trim());
    }
}
