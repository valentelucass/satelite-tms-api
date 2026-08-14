package com.example.satelite.services.etl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

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

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.EslLoteResponseDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;

/**
 * Recupera XMLs Vedacit de uma lista local de NF-es, sem tocar no cursor produtivo
 * ou no fluxo de canhotos. O arquivo deve conter uma chave NF-e de 44 dígitos por linha.
 */
@Component
@Order(-20)
@ConditionalOnProperty(name = "vedacit.recovery.enabled", havingValue = "true")
public class RecuperacaoDirigidaVedacitRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(RecuperacaoDirigidaVedacitRunner.class);
    private static final Pattern CHAVE_NFE = Pattern.compile("\\d{44}");
    private static final int LIMITE_PADRAO = 50;
    private static final int LIMITE_MAXIMO = 200;

    private final RodogarciaClient rodogarciaClient;
    private final EslRequestPolicyService eslRequestPolicyService;
    private final EtlRegistroService etlRegistroService;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private int exitCode;

    public RecuperacaoDirigidaVedacitRunner(
            RodogarciaClient rodogarciaClient,
            EslRequestPolicyService eslRequestPolicyService,
            EtlRegistroService etlRegistroService,
            Environment environment,
            ConfigurableApplicationContext context
    ) {
        this.rodogarciaClient = rodogarciaClient;
        this.eslRequestPolicyService = eslRequestPolicyService;
        this.etlRegistroService = etlRegistroService;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        try {
            List<String> chaves = carregarChaves(obterArquivoObrigatorio(), obterLimiteSeguro());
            String token = obterTokenObrigatorio();
            ResultadoRecuperacao resultado = recuperar(chaves, token);
            exitCode = resultado.erros() == 0 ? 0 : 1;
            log.info(
                    "🏁 [VEDACIT] Recuperação dirigida finalizada. selecionadas={} encontradas={} enviadas={} ja_processadas={} ignoradas={} erros={}",
                    chaves.size(), resultado.encontradas(), resultado.enviadas(), resultado.jaProcessadas(),
                    resultado.ignoradas(), resultado.erros()
            );
        } catch (Exception e) {
            exitCode = 1;
            log.error("💥 [VEDACIT] Falha crítica na recuperação dirigida de NF-es.", e);
        } finally {
            int codigoSpring = SpringApplication.exit(context, () -> exitCode);
            System.exit(codigoSpring);
        }
    }

    ResultadoRecuperacao recuperar(List<String> chaves, String token) {
        int encontradas = 0;
        int enviadas = 0;
        int jaProcessadas = 0;
        int ignoradas = 0;
        int erros = 0;

        for (String chaveNfe : chaves) {
            try {
                EslLoteResponseDTO lote = eslRequestPolicyService.executarComTelemetria(
                        EslRequestContext.criar("VEDACIT", "VEDACIT_XML_RECOVERY"),
                        () -> rodogarciaClient.buscarOcorrencias(
                                "Bearer " + token, null, chaveNfe, null, EtapaVedacit.EMISSAO_XML.codigoOcorrencia()
                        )
                );
                List<EslOcorrenciaDTO> ocorrencias = lote != null && lote.data() != null ? lote.data() : List.of();
                List<EslOcorrenciaDTO> emissoes = ocorrencias.stream()
                        .filter(etlRegistroService::ehCteEmitido)
                        .toList();
                if (emissoes.isEmpty()) {
                    log.warn("⚠️ [VEDACIT] NF {}: emissão 110 não encontrada na ESL; nenhum envio realizado.", chaveNfe);
                    continue;
                }

                encontradas += emissoes.size();
                for (EslOcorrenciaDTO ocorrencia : emissoes) {
                    ResultadoRegistro resultado = etlRegistroService.processarEmissaoXmlVedacit(
                            "Bearer " + token, null, ocorrencia
                    );
                    switch (resultado) {
                        case ENVIADO -> enviadas++;
                        case JA_PROCESSADO -> jaProcessadas++;
                        case IGNORADO -> ignoradas++;
                        default -> erros++;
                    }
                }
            } catch (Exception e) {
                erros++;
                log.error("❌ [VEDACIT] NF {}: falha na recuperação dirigida: {}", chaveNfe, e.getMessage());
            }
        }

        return new ResultadoRecuperacao(encontradas, enviadas, jaProcessadas, ignoradas, erros);
    }

    static List<String> carregarChaves(Path arquivo, int limite) throws IOException {
        try (var linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            return linhas.map(String::trim)
                    .filter(chave -> CHAVE_NFE.matcher(chave).matches())
                    .distinct()
                    .limit(limite)
                    .toList();
        }
    }

    private Path obterArquivoObrigatorio() {
        String valor = environment.getProperty("vedacit.recovery.nfe-file", "").trim();
        if (valor.isEmpty()) {
            throw new IllegalArgumentException("Informe --vedacit.recovery.nfe-file com uma NF-e de 44 dígitos por linha");
        }
        Path arquivo = Path.of(valor).toAbsolutePath().normalize();
        if (!Files.isRegularFile(arquivo)) {
            throw new IllegalArgumentException("Arquivo de NF-es não encontrado: " + arquivo);
        }
        return arquivo;
    }

    private String obterTokenObrigatorio() {
        String token = environment.getProperty("RODOGARCIA_TOKEN_VEDACIT", "").trim();
        if (token.isEmpty()) {
            throw new IllegalStateException("RODOGARCIA_TOKEN_VEDACIT não configurado");
        }
        return token;
    }

    private int obterLimiteSeguro() {
        String valor = environment.getProperty("vedacit.recovery.max-items", String.valueOf(LIMITE_PADRAO));
        try {
            return Math.max(1, Math.min(LIMITE_MAXIMO, Integer.parseInt(valor.trim())));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("vedacit.recovery.max-items deve ser inteiro positivo", e);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    record ResultadoRecuperacao(int encontradas, int enviadas, int jaProcessadas, int ignoradas, int erros) {
    }
}
