package com.example.satelite.utils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

class VedacitImageCompressionVisualTest {

    private static final String CHAVE_CTE_EXEMPLO = "35260560960473000758570030000447631295287632";
    private static final String URL_ASSINADA_PROPERTY = "vedacit.visual.image.url";
    private static final String URL_ASSINADA_ENV = "VEDACIT_VISUAL_IMAGE_URL";

    private static final Path DIRETORIO_TEMP = Path.of("C:\\temp");
    private static final Path ARQUIVO_FALLBACK = DIRETORIO_TEMP.resolve("teste_vedacit.jpg");
    private static final Path ARQUIVO_ORIGINAL = DIRETORIO_TEMP.resolve("canhoto_vedacit_original.jpg");
    private static final Path ARQUIVO_COMPRIMIDO = DIRETORIO_TEMP.resolve("canhoto_vedacit_comprimido.jpg");

    @Test
    void deveGerarArquivosParaValidacaoVisualDaCompressaoVedacit() throws Exception {
        byte[] imagemOriginal = obterImagemOriginal();

        Files.createDirectories(DIRETORIO_TEMP);
        Files.write(ARQUIVO_ORIGINAL, imagemOriginal);

        byte[] imagemComprimida = ImageUtils.comprimirImagemParaVedacit(imagemOriginal);
        Files.write(ARQUIVO_COMPRIMIDO, imagemComprimida);

        imprimirMetricas(imagemOriginal, imagemComprimida);
        assertImagemComprimidaValida(imagemComprimida);
    }

    private byte[] obterImagemOriginal() throws IOException {
        try {
            return baixarImagemRealDaEsl();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Download real via ImageDownloader interrompido para CTe "
                    + CHAVE_CTE_EXEMPLO + ". Usando fallback local. Motivo: " + e.getMessage());
            return lerFallbackLocal();
        } catch (Exception e) {
            System.out.println("Download real via ImageDownloader indisponivel para CTe "
                    + CHAVE_CTE_EXEMPLO + ". Usando fallback local. Motivo: " + e.getMessage());
            return lerFallbackLocal();
        }
    }

    private byte[] baixarImagemRealDaEsl() throws IOException, InterruptedException {
        String urlAssinada = obterUrlAssinadaConfigurada();
        if (urlAssinada == null) {
            throw new IOException("URL assinada nao configurada. Informe -D"
                    + URL_ASSINADA_PROPERTY + " ou env " + URL_ASSINADA_ENV);
        }

        ImageDownloader downloader = new ImageDownloader(5, 30, 1, 0);
        return downloader.baixarImagemDaUrl(urlAssinada, CHAVE_CTE_EXEMPLO);
    }

    private String obterUrlAssinadaConfigurada() {
        String urlAssinada = System.getProperty(URL_ASSINADA_PROPERTY);
        if (urlAssinada == null || urlAssinada.isBlank()) {
            urlAssinada = System.getenv(URL_ASSINADA_ENV);
        }

        return urlAssinada == null || urlAssinada.isBlank() ? null : urlAssinada;
    }

    private byte[] lerFallbackLocal() throws IOException {
        Assumptions.assumeTrue(
                Files.exists(ARQUIVO_FALLBACK),
                () -> "Download ESL indisponivel e fallback ausente em " + ARQUIVO_FALLBACK
        );

        return Files.readAllBytes(ARQUIVO_FALLBACK);
    }

    private void imprimirMetricas(byte[] imagemOriginal, byte[] imagemComprimida) {
        double tamanhoAntesKb = imagemOriginal.length / 1024.0;
        double tamanhoDepoisKb = imagemComprimida.length / 1024.0;
        double reducaoPercentual = 100.0 * (1.0 - ((double) imagemComprimida.length / imagemOriginal.length));

        System.out.println("Arquivo original salvo em: " + ARQUIVO_ORIGINAL.toAbsolutePath());
        System.out.println("Arquivo comprimido salvo em: " + ARQUIVO_COMPRIMIDO.toAbsolutePath());
        System.out.printf(Locale.ROOT, "Tamanho ANTES: %.2f KB%n", tamanhoAntesKb);
        System.out.printf(Locale.ROOT, "Tamanho DEPOIS: %.2f KB%n", tamanhoDepoisKb);
        System.out.printf(Locale.ROOT, "Reducao: %.2f%%%n", reducaoPercentual);
    }

    private void assertImagemComprimidaValida(byte[] imagemComprimida) throws IOException {
        assertTrue(imagemComprimida.length > 0);

        BufferedImage imagem = ImageIO.read(new ByteArrayInputStream(imagemComprimida));
        assertNotNull(imagem);
        assertTrue(
                Math.max(imagem.getWidth(), imagem.getHeight()) <= 1024,
                () -> "Maior dimensao deveria ser no maximo 1024px, mas imagem ficou com "
                        + imagem.getWidth() + "x" + imagem.getHeight()
        );
    }
}
