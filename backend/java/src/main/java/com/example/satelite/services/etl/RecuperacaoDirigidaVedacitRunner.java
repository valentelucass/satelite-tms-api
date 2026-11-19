package com.example.satelite.services.etl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
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
            List<String> chaves = carregarAlvos(obterArquivoObrigatorio());
            if (!environment.getProperty("vedacit.recovery.drain-enabled", Boolean.class, true))
                chaves = chaves.stream().limit(obterLimiteSeguro()).toList();
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

        boolean previa = environment.getProperty("vedacit.recovery.preview", Boolean.class, true);
        long intervalo = environment.getProperty("vedacit.recovery.interval-ms", Long.class, 1000L);
        if (intervalo < 0 || intervalo > 60000) throw new IllegalArgumentException("Intervalo de recuperação inválido");
        Set<String> ctesTentados = new HashSet<>();
        int falhasConsecutivas = 0;
        for (String alvo : chaves) {
            if (Thread.currentThread().isInterrupted() || falhasConsecutivas >= 3) break;
            String[] par = alvo.split(";", -1);
            String chaveNfe = par[0];
            String chaveCte = par.length == 2 ? par[1] : null;
            try {
                Long cursor = null;
                Set<Long> cursores = new HashSet<>();
                boolean encontrou = false;
                while (!Thread.currentThread().isInterrupted()) {
                Long inicioPagina = cursor;
                EslLoteResponseDTO lote = eslRequestPolicyService.executarComTelemetria(
                        EslRequestContext.criar("VEDACIT", "VEDACIT_XML_RECOVERY"),
                        () -> rodogarciaClient.buscarOcorrencias(
                                "Bearer " + token, inicioPagina, chaveNfe, null, EtapaVedacit.EMISSAO_XML.codigoOcorrencia()
                        )
                );
                if (lote == null) break;
                List<EslOcorrenciaDTO> ocorrencias = lote.data() != null ? lote.data() : List.of();
                List<EslOcorrenciaDTO> emissoes = ocorrencias.stream()
                        .filter(etlRegistroService::ehCteEmitido)
                        .filter(o -> o.invoice() != null && chaveNfe.equals(o.invoice().key()))
                        .filter(o -> o.freight() != null && o.freight().cteKey() != null
                                && o.freight().cteKey().matches("\\d{44}"))
                        .filter(o -> chaveCte == null || chaveCte.equals(o.freight().cteKey()))
                        .toList();
                for (EslOcorrenciaDTO ocorrencia : emissoes) {
                    encontrou = true;
                    if (!ctesTentados.add(ocorrencia.freight().cteKey())) continue;
                    encontradas++;
                    if (previa) { ignoradas++; continue; }
                    if (ctesTentados.size() > 1 && intervalo > 0) Thread.sleep(intervalo);
                    ResultadoRegistro resultado = etlRegistroService.processarEmissaoXmlVedacit(
                            "Bearer " + token, null, ocorrencia
                    );
                    switch (resultado) {
                        case ENVIADO -> enviadas++;
                        case JA_PROCESSADO -> jaProcessadas++;
                        case IGNORADO -> ignoradas++;
                        default -> erros++;
                    }
                    falhasConsecutivas = resultado.erro() ? falhasConsecutivas + 1 : 0;
                    if (falhasConsecutivas >= 3) break;
                }
                if (falhasConsecutivas >= 3 || ocorrencias.isEmpty() || lote.paging() == null || lote.paging().nextId() == null) break;
                Long proximo = lote.paging().nextId();
                if (!cursores.add(proximo) || (cursor != null && proximo <= cursor))
                    throw new IllegalStateException("Paginação ESL não avançou; recuperação interrompida para esta NF-e");
                cursor = proximo;
                }
                if (!encontrou) {
                    ignoradas++;
                    log.warn("[VEDACIT][RECOVERY] Emissão 110 do par solicitado ausente; nenhum envio para NF final {}.", chaveNfe.substring(38));
                }
            } catch (Exception e) {
                erros++;
                falhasConsecutivas++;
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.error("[VEDACIT][RECOVERY] NF final {}: falha controlada ({})", chaveNfe.substring(38), e.getClass().getSimpleName());
            }
        }

        return new ResultadoRecuperacao(encontradas, enviadas, jaProcessadas, ignoradas, erros);
    }

    /** Manifesto explícito de NF-e;CT-e. O modo legado de NF-e isolada continua aceito. */
    static List<String> carregarAlvos(Path arquivo) throws IOException {
        try (var linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            return linhas.map(l -> l.replace("\uFEFF", "").trim())
                    .filter(l -> !l.isBlank() && !l.startsWith("#"))
                    .peek(l -> { if (!l.matches("\\d{44}(;\\d{44})?"))
                        throw new IllegalArgumentException("Manifesto inválido; esperado NF-e;CT-e de 44 dígitos"); })
                    .distinct().toList();
        }
    }

    static List<String> carregarChaves(Path arquivo, int limite) throws IOException {
        try (var linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            return linhas.map(linha -> linha == null ? "" : linha.trim())
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
