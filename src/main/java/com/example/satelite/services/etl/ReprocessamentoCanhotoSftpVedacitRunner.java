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

import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpClient;

/** Lote foreground, limitado e exclusivamente SFTP para canhotos Vedacit pendentes. */
@Component
@Order(-11)
@ConditionalOnProperty(name = "vedacit.sftp-receipt-batch.enabled", havingValue = "true")
public class ReprocessamentoCanhotoSftpVedacitRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReprocessamentoCanhotoSftpVedacitRunner.class);
    private static final int LIMITE_PADRAO_SEGURO = 10;
    private static final int LIMITE_MAXIMO = 100;
    private static final int LIMITE_RODADAS_DRENO_PADRAO = 50;
    private static final int LIMITE_RODADAS_DRENO_MAXIMO = 100;
    private static final int PAUSA_ENTRE_RODADAS_PADRAO_MS = 30000;
    private static final int PAUSA_ENTRE_RODADAS_MAXIMA_MS = 300000;

    private final EtlRepescagemService etlRepescagemService;
    private final VedacitSftpClient vedacitSftpClient;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private int exitCode;

    public ReprocessamentoCanhotoSftpVedacitRunner(
            EtlRepescagemService etlRepescagemService,
            VedacitSftpClient vedacitSftpClient,
            Environment environment,
            ConfigurableApplicationContext context
    ) {
        this.etlRepescagemService = etlRepescagemService;
        this.vedacitSftpClient = vedacitSftpClient;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        try {
            validarModoSftpExclusivo();
            vedacitSftpClient.verificarDisponibilidade();
            int limite = obterInteiro("vedacit.sftp-receipt-batch.max-items", LIMITE_PADRAO_SEGURO, LIMITE_MAXIMO);
            long intervaloMs = obterInteiro("vedacit.sftp-receipt-batch.interval-ms", 1000, 60000);
            boolean drenarAteOcioso = environment.getProperty(
                    "vedacit.sftp-receipt-batch.drain-until-idle",
                    Boolean.class,
                    false
            );
            int maximoRodadas = obterInteiro(
                    "vedacit.sftp-receipt-batch.drain-max-rounds",
                    LIMITE_RODADAS_DRENO_PADRAO,
                    LIMITE_RODADAS_DRENO_MAXIMO
            );
            long pausaEntreRodadasMs = obterInteiro(
                    "vedacit.sftp-receipt-batch.drain-between-rounds-ms",
                    PAUSA_ENTRE_RODADAS_PADRAO_MS,
                    PAUSA_ENTRE_RODADAS_MAXIMA_MS
            );
            EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit resultado = executarRodadas(
                    limite,
                    intervaloMs,
                    drenarAteOcioso,
                    maximoRodadas,
                    pausaEntreRodadasMs
            );
            exitCode = resultado.concluidoSemErro() ? 0 : 1;
            log.info(
                    "🏁 [VEDACIT] Lote SFTP de canhotos finalizado. selecionados={} enviados={} pendentes={} erros={}",
                    resultado.selecionados(), resultado.enviados(), resultado.pendentes(), resultado.erros()
            );
        } catch (Exception e) {
            exitCode = 1;
            log.error("💥 [VEDACIT] Falha crítica no lote SFTP de canhotos.", e);
        } finally {
            int codigoSpring = SpringApplication.exit(context, () -> exitCode);
            System.exit(codigoSpring);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit executarRodadas(
            int limite,
            long intervaloMs,
            boolean drenarAteOcioso,
            int maximoRodadas,
            long pausaEntreRodadasMs
    ) {
        int selecionados = 0;
        int enviados = 0;
        int pendentes = 0;
        int erros = 0;

        for (int rodada = 1; rodada <= maximoRodadas; rodada++) {
            EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit rodadaResultado =
                    etlRepescagemService.reprocessarCanhotosPendentesFotoSftpVedacit(limite, intervaloMs);
            selecionados += rodadaResultado.selecionados();
            enviados += rodadaResultado.enviados();
            pendentes += rodadaResultado.pendentes();
            erros += rodadaResultado.erros();

            log.info(
                    "🏁 [VEDACIT] Rodada SFTP {}/{} finalizada. selecionados={} enviados={} pendentes={} erros={}",
                    rodada,
                    drenarAteOcioso ? maximoRodadas : 1,
                    rodadaResultado.selecionados(),
                    rodadaResultado.enviados(),
                    rodadaResultado.pendentes(),
                    rodadaResultado.erros()
            );

            boolean deveContinuar = drenarAteOcioso
                    && rodadaResultado.erros() == 0
                    && rodadaResultado.selecionados() == limite
                    && rodadaResultado.enviados() > 0
                    && rodada < maximoRodadas;
            if (!deveContinuar) {
                if (drenarAteOcioso && rodada == maximoRodadas) {
                    log.warn(
                            "⚠️ [VEDACIT] Dreno SFTP atingiu o limite de {} rodadas; o monitor retomará na próxima consulta.",
                            maximoRodadas
                    );
                }
                break;
            }

            log.info(
                    "⏳ [VEDACIT] Próxima rodada SFTP em {} segundo(s), pois ainda há pelo menos {} candidatos.",
                    pausaEntreRodadasMs / 1000,
                    limite
            );
            try {
                Thread.sleep(pausaEntreRodadasMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("⚠️ [VEDACIT] Dreno SFTP interrompido antes da próxima rodada.");
                break;
            }
        }

        return new EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit(
                selecionados,
                enviados,
                pendentes,
                erros
        );
    }

    private void validarModoSftpExclusivo() {
        if (!environment.getProperty("SFTP_RODOGARCIA_ENABLED", Boolean.class, false)
                || !environment.getProperty("VEDACIT_SFTP_RECEIPT_ONLY", Boolean.class, false)) {
            throw new IllegalStateException("Lote Vedacit exige SFTP habilitado e fallback ESL desabilitado");
        }
        propriedadeObrigatoria("SFTP_RODOGARCIA_HOST");
        propriedadeObrigatoria("SFTP_RODOGARCIA_USERNAME");
        propriedadeObrigatoria("SFTP_RODOGARCIA_PASSWORD");
        propriedadeObrigatoria("SFTP_RODOGARCIA_CLIENT_PATH");
        propriedadeObrigatoria("SFTP_RODOGARCIA_HOST_KEY_SHA256");
    }

    private void propriedadeObrigatoria(String propriedade) {
        String valor = environment.getProperty(propriedade);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Configuração SFTP obrigatória ausente: " + propriedade);
        }
    }

    private int obterInteiro(String propriedade, int padrao, int maximo) {
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
