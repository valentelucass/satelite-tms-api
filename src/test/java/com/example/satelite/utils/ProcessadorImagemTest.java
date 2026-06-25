package com.example.satelite.utils;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public class ProcessadorImagemTest {

    private static final Path ENTRADA_PADRAO = Path.of("C:\\temp\\canhoto_original.jpg");
    private static final Path SAIDA_PRODUCAO_PADRAO = Path.of("C:\\temp\\canhoto_resultado_producao.jpg");

    private static final String PREFIXO_BASE64_PPG = "data:image/jpeg;base64,";
    private static final double CROP_INICIO_PRODUCAO = 0.60;
    private static final double CROP_ALTURA_PRODUCAO = 0.20;

    public static void main(String[] args) throws Exception {
        Path entrada = args.length > 0 ? Path.of(args[0]) : ENTRADA_PADRAO;

        gerarResultadoProducao(entrada);
    }

    @Test
    void geraResultadoVisualComPipelineDeProducao() throws Exception {
        Assumptions.assumeTrue(
                Files.exists(ENTRADA_PADRAO),
                () -> "Imagem de laboratorio ausente em " + ENTRADA_PADRAO
        );

        gerarResultadoProducao(ENTRADA_PADRAO);
    }

    private static void gerarResultadoProducao(Path entrada) throws IOException {
        if (!Files.exists(entrada)) {
            throw new IOException("Imagem original nao encontrada: " + entrada);
        }

        ImageUtils imageUtils = new ImageUtils(CROP_INICIO_PRODUCAO, CROP_ALTURA_PRODUCAO);
        String imagemPpgBase64 = imageUtils.converterParaBase64Ppg(Files.readAllBytes(entrada));
        byte[] jpegBytes = decodificarJpegPpg(imagemPpgBase64);

        Path diretorioSaida = SAIDA_PRODUCAO_PADRAO.getParent();
        if (diretorioSaida != null) {
            Files.createDirectories(diretorioSaida);
        }

        Files.write(SAIDA_PRODUCAO_PADRAO, jpegBytes);

        System.out.println("Entrada: " + entrada.toAbsolutePath());
        System.out.println("Resultado producao: " + SAIDA_PRODUCAO_PADRAO.toAbsolutePath());
    }

    private static byte[] decodificarJpegPpg(String imagemPpgBase64) throws IOException {
        if (imagemPpgBase64 == null || !imagemPpgBase64.startsWith(PREFIXO_BASE64_PPG)) {
            throw new IOException("Imagem PPG gerada sem prefixo esperado: " + PREFIXO_BASE64_PPG);
        }

        return Base64.getDecoder().decode(imagemPpgBase64.substring(PREFIXO_BASE64_PPG.length()));
    }
}
