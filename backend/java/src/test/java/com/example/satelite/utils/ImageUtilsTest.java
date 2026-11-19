package com.example.satelite.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Random;

class ImageUtilsTest {

    private static final String PREFIXO_BASE64_PPG = "data:image/jpeg;base64,";

    @Test
    void deveRecortarFaixaCanhotoRedimensionarSemDistorcerEGravarJpegComDpiPpg() throws Exception {
        ImageUtils imageUtils = new ImageUtils(0.60, 0.20);
        byte[] imagemOriginal = criarImagemComFaixaCanhotoVermelha();

        String dataUri = imageUtils.converterParaBase64Ppg(imagemOriginal);

        assertTrue(dataUri.startsWith(PREFIXO_BASE64_PPG));
        byte[] jpegBytes = Base64.getDecoder().decode(dataUri.substring(PREFIXO_BASE64_PPG.length()));
        BufferedImage imagemFinal = ImageIO.read(new ByteArrayInputStream(jpegBytes));

        assertNotNull(imagemFinal);
        assertEquals(1536, imagemFinal.getWidth());
        assertEquals(240, imagemFinal.getHeight());
        assertPixelCentralVermelho(imagemFinal);
        assertLateraisBrancas(imagemFinal);
        assertJfifDpi(jpegBytes, 150);
    }

    @Test
    void deveComprimirImagemParaVedacitComoJpegLimitandoMaiorDimensao() throws Exception {
        byte[] imagemOriginal = criarImagemGrandeParaVedacit();

        byte[] imagemComprimida = ImageUtils.comprimirImagemParaVedacit(imagemOriginal);

        assertTrue(imagemComprimida.length > 0);
        BufferedImage imagemFinal = ImageIO.read(new ByteArrayInputStream(imagemComprimida));

        assertNotNull(imagemFinal);
        assertEquals(1024, imagemFinal.getWidth());
        assertEquals(512, imagemFinal.getHeight());
    }

    @Test
    void deveRenderizarPrimeiraPaginaPdfParaJpegVedacit() throws Exception {
        byte[] arquivoPdf = criarPdfSimples();

        byte[] resultado = ImageUtils.comprimirImagemParaVedacit(arquivoPdf);

        assertTrue(resultado.length > 0);
        assertTrue(resultado.length <= 400 * 1024);

        BufferedImage imagemFinal = ImageIO.read(new ByteArrayInputStream(resultado));

        assertNotNull(imagemFinal);
        assertTrue(Math.max(imagemFinal.getWidth(), imagemFinal.getHeight()) <= 1024);
    }

    @Test
    void deveFalharRapidoQuandoCanhotoVedacitComprimidoExceder400Kb() throws Exception {
        byte[] imagemOriginal = criarImagemRuidosaGrandeParaVedacit();

        IllegalArgumentException erro = assertThrows(
                IllegalArgumentException.class,
                () -> ImageUtils.comprimirImagemParaVedacit(imagemOriginal)
        );

        assertEquals(
                "O canhoto comprimido excede o limite máximo de 400KB suportado pelo destino",
                erro.getMessage()
        );
    }

    private byte[] criarImagemComFaixaCanhotoVermelha() throws IOException {
        BufferedImage imagem = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = imagem.createGraphics();

        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, 1000, 600);
        g2d.setColor(Color.RED);
        g2d.fillRect(0, 600, 1000, 200);
        g2d.setColor(Color.BLUE);
        g2d.fillRect(0, 800, 1000, 200);
        g2d.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(imagem, "png", baos);
        return baos.toByteArray();
    }

    private byte[] criarImagemGrandeParaVedacit() throws IOException {
        BufferedImage imagem = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = imagem.createGraphics();

        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, 2000, 1000);
        g2d.setColor(Color.BLACK);
        g2d.fillRect(50, 50, 1900, 900);
        g2d.setColor(Color.RED);
        g2d.fillRect(200, 300, 1600, 200);
        g2d.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(imagem, "png", baos);
        return baos.toByteArray();
    }

    private byte[] criarPdfSimples() throws IOException {
        try (PDDocument documento = new PDDocument()) {
            documento.addPage(new PDPage());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            documento.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] criarImagemRuidosaGrandeParaVedacit() throws IOException {
        BufferedImage imagem = new BufferedImage(2000, 2000, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);

        for (int y = 0; y < imagem.getHeight(); y++) {
            for (int x = 0; x < imagem.getWidth(); x++) {
                imagem.setRGB(x, y, random.nextInt(0x1000000));
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(imagem, "png", baos);
        return baos.toByteArray();
    }

    private void assertPixelCentralVermelho(BufferedImage imagemFinal) {
        Color pixelCentral = new Color(imagemFinal.getRGB(768, 120));

        assertTrue(pixelCentral.getRed() > 180);
        assertTrue(pixelCentral.getGreen() < 80);
        assertTrue(pixelCentral.getBlue() < 80);
    }

    private void assertLateraisBrancas(BufferedImage imagemFinal) {
        assertPixelBranco(new Color(imagemFinal.getRGB(20, 120)));
        assertPixelBranco(new Color(imagemFinal.getRGB(1515, 120)));
    }

    private void assertPixelBranco(Color pixel) {
        assertTrue(pixel.getRed() > 230);
        assertTrue(pixel.getGreen() > 230);
        assertTrue(pixel.getBlue() > 230);
    }

    private void assertJfifDpi(byte[] jpegBytes, int dpiEsperado) throws IOException {
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(jpegBytes));
        assertNotNull(iis);

        try (iis) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            assertTrue(readers.hasNext());

            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                IIOMetadata metadata = reader.getImageMetadata(0);
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_jpeg_image_1.0");
                NodeList jfifNodes = root.getElementsByTagName("app0JFIF");

                assertTrue(jfifNodes.getLength() > 0);
                IIOMetadataNode jfif = (IIOMetadataNode) jfifNodes.item(0);
                assertEquals("1", jfif.getAttribute("resUnits"));
                assertEquals(String.valueOf(dpiEsperado), jfif.getAttribute("Xdensity"));
                assertEquals(String.valueOf(dpiEsperado), jfif.getAttribute("Ydensity"));
            } finally {
                reader.dispose();
            }
        }
    }
}
