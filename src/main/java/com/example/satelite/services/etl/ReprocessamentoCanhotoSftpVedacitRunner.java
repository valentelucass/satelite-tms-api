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

import java.util.List;

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
    private static final int LIMITE_ERROS_POR_RODADA_PADRAO = 25;

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
            int limiteErrosPorRodada = obterInteiroNaoNegativo(
                    "vedacit.sftp-receipt-batch.drain-max-errors-per-round",
                    LIMITE_ERROS_POR_RODADA_PADRAO,
                    limite
            );
            List<String> nfesComArquivoSftp = vedacitSftpClient.listarComprovantes().stream()
                    .map(documento -> documento.chaveNfe()).distinct().toList();
            long nfesCandidatas = etlRepescagemService.contarNfesCandidatasCanhotoVedacitSftp(nfesComArquivoSftp);
            long lotesEstimados = (nfesCandidatas + limite - 1L) / limite;
            log.info(
                    "📊 [VEDACIT][SFTP] Inventário | comprovantes={} | NF-e elegíveis={} | plano={} lote(s) de até {}",
                    nfesComArquivoSftp.size(),
                    nfesCandidatas,
                    lotesEstimados,
                    limite
            );
            EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit resultado = executarRodadas(
                    limite,
                    intervaloMs,
                    drenarAteOcioso,
                    maximoRodadas,
                    pausaEntreRodadasMs,
                    limiteErrosPorRodada,
                    nfesComArquivoSftp
            );
            exitCode = resultado.concluidoSemErro() ? 0 : 1;
            log.info(
                    "🏁 [VEDACIT][SFTP] Finalizado | selecionados={} enviados={} pendentes={} erros={} ignorados={}",
                    resultado.selecionados(), resultado.enviados(), resultado.pendentes(), resultado.erros(),
                    resultado.ignorados()
            );
        } catch (Exception e) {
            exitCode = 2;
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
            long pausaEntreRodadasMs,
            int limiteErrosPorRodada,
            List<String> nfesComArquivoSftp
    ) {
        int selecionados = 0;
        int enviados = 0;
        int pendentes = 0;
        int erros = 0;
        int ignorados = 0;

        for (int rodada = 1; rodada <= maximoRodadas; rodada++) {
            EtlRepescagemService.ResultadoReprocessamentoCanhotoVedacit rodadaResultado =
                    etlRepescagemService.reprocessarCanhotosPendentesFotoSftpVedacit(limite, intervaloMs, nfesComArquivoSftp);
            selecionados += rodadaResultado.selecionados();
            enviados += rodadaResultado.enviados();
            pendentes += rodadaResultado.pendentes();
            erros += rodadaResultado.erros();
            ignorados += rodadaResultado.ignorados();

            log.info(
                    "🏁 [VEDACIT][SFTP] Rodada {}/{} | selecionados={} enviados={} pendentes={} erros={} ignorados={}",
                    rodada,
                    drenarAteOcioso ? maximoRodadas : 1,
                    rodadaResultado.selecionados(),
                    rodadaResultado.enviados(),
                    rodadaResultado.pendentes(),
                    rodadaResultado.erros(),
                    rodadaResultado.ignorados()
            );

            boolean deveContinuar = drenarAteOcioso
                    && rodadaResultado.selecionados() == limite
                    && (rodadaResultado.enviados() > 0 || rodadaResultado.ignorados() > 0)
                    && rodadaResultado.erros() < limiteErrosPorRodada
                    && rodada < maximoRodadas;
            if (!deveContinuar) {
                if (drenarAteOcioso && rodadaResultado.erros() >= limiteErrosPorRodada) {
                    log.warn(
                            "⚠️ [VEDACIT][SFTP] Dreno pausado: rodada teve {} erro(s), limite seguro={}. Os demais registros não foram tentados.",
                            rodadaResultado.erros(),
                            limiteErrosPorRodada
                    );
                }
                if (drenarAteOcioso && rodada == maximoRodadas) {
                    log.warn(
                            "⚠️ [VEDACIT] Dreno SFTP atingiu o limite de {} rodadas; o monitor retomará na próxima consulta.",
                            maximoRodadas
                    );
                }
                break;
            }

            log.info(
                    "⏳ [VEDACIT][SFTP] Próxima rodada em {} segundo(s) | erros da rodada {}/{} | continuando com os próximos candidatos.",
                    pausaEntreRodadasMs / 1000,
                    rodadaResultado.erros(),
                    limiteErrosPorRodada
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
                erros,
                ignorados
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

    private int obterInteiroNaoNegativo(String propriedade, int padrao, int maximo) {
        String valor = environment.getProperty(propriedade, String.valueOf(padrao));
        try {
            int numero = Integer.parseInt(valor.trim());
            if (numero < 0 || numero > maximo) {
                throw new IllegalArgumentException(propriedade + " deve estar entre 0 e " + maximo);
            }
            return numero;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(propriedade + " deve ser inteiro não negativo", e);
        }
    }
}
