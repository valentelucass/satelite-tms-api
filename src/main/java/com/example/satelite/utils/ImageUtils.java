package com.example.satelite.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

@Component
public class ImageUtils {

    private static final String PREFIXO_BASE64_PPG = "data:image/jpeg;base64,";
    private static final double CROP_INICIO_RATIO_PADRAO = 0.60;
    private static final double CROP_ALTURA_RATIO_PADRAO = 0.20;

    private static final int LARGURA_PPG = 1536;
    private static final int ALTURA_PPG = 240;
    private static final int DPI_PPG = 150;

    private final double cropInicioRatio;
    private final double cropAlturaRatio;

    public ImageUtils(
            @Value("${app.ppg.image-crop-start-ratio:0.60}") double cropInicioRatio,
            @Value("${app.ppg.image-crop-height-ratio:0.20}") double cropAlturaRatio
    ) {
        this.cropInicioRatio = normalizarCropInicioRatio(cropInicioRatio);
        this.cropAlturaRatio = normalizarCropAlturaRatio(cropAlturaRatio);
    }

    /**
     * Recebe os bytes originais, recorta a faixa do canhoto, normaliza para o padrao PPG e devolve o Base64 final.
     */
    public String converterParaBase64Ppg(byte[] imagemOriginalBytes) throws IOException {
        if (imagemOriginalBytes == null || imagemOriginalBytes.length == 0) {
            throw new IllegalArgumentException("Imagem original ausente para conversao PPG");
        }

        BufferedImage imagemOriginal = ImageIO.read(new ByteArrayInputStream(imagemOriginalBytes));
        if (imagemOriginal == null) {
            throw new IOException("Formato de imagem nao suportado para conversao PPG");
        }

        BufferedImage imagemRgb = converterParaRgbComFundoBranco(imagemOriginal);
        BufferedImage recorteCanhoto = recortarFaixaCanhoto(imagemRgb);
        BufferedImage imagemPpg = redimensionarParaPpg(recorteCanhoto);
        byte[] imagemFinalBytes = escreverJpegComDpi(imagemPpg, DPI_PPG);

        String base64Bruto = Base64.getEncoder().encodeToString(imagemFinalBytes);
        return PREFIXO_BASE64_PPG + base64Bruto;
    }

    private BufferedImage converterParaRgbComFundoBranco(BufferedImage imagemOriginal) {
        if (imagemOriginal.getType() == BufferedImage.TYPE_INT_RGB) {
            return imagemOriginal;
        }

        BufferedImage imagemRgb = new BufferedImage(
                imagemOriginal.getWidth(),
                imagemOriginal.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g2d = imagemRgb.createGraphics();
        aplicarRenderizacaoAltaQualidade(g2d);
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, imagemRgb.getWidth(), imagemRgb.getHeight());
        g2d.drawImage(imagemOriginal, 0, 0, null);
        g2d.dispose();

        return imagemRgb;
    }

    private BufferedImage recortarFaixaCanhoto(BufferedImage imagemOriginal) {
        int larguraOriginal = imagemOriginal.getWidth();
        int alturaOriginal = imagemOriginal.getHeight();
        int eixoY = (int) Math.round(alturaOriginal * cropInicioRatio);
        int alturaCrop = (int) Math.round(alturaOriginal * cropAlturaRatio);

        eixoY = Math.max(0, Math.min(alturaOriginal - 1, eixoY));
        alturaCrop = Math.max(1, alturaCrop);

        int yFinal = Math.min(alturaOriginal, eixoY + alturaCrop);
        if (yFinal <= eixoY) {
            eixoY = Math.max(0, alturaOriginal - 1);
            yFinal = alturaOriginal;
        }

        return imagemOriginal.getSubimage(0, eixoY, larguraOriginal, yFinal - eixoY);
    }

    private BufferedImage redimensionarParaPpg(BufferedImage recorteCanhoto) {
        BufferedImage imagemPpg = new BufferedImage(LARGURA_PPG, ALTURA_PPG, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = imagemPpg.createGraphics();

        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, LARGURA_PPG, ALTURA_PPG);
        aplicarRenderizacaoAltaQualidade(g2d);

        double escala = Math.min(
                (double) LARGURA_PPG / recorteCanhoto.getWidth(),
                (double) ALTURA_PPG / recorteCanhoto.getHeight()
        );
        int larguraFinal = Math.max(1, (int) Math.round(recorteCanhoto.getWidth() * escala));
        int alturaFinal = Math.max(1, (int) Math.round(recorteCanhoto.getHeight() * escala));
        int x = (LARGURA_PPG - larguraFinal) / 2;
        int y = (ALTURA_PPG - alturaFinal) / 2;

        g2d.drawImage(recorteCanhoto, x, y, larguraFinal, alturaFinal, null);
        g2d.dispose();

        return imagemPpg;
    }

    private byte[] escreverJpegComDpi(BufferedImage imagem, int dpi) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("Nenhum escritor JPEG disponivel para conversao PPG");
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.92f);
        }

        ImageTypeSpecifier tipoImagem = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB);
        IIOMetadata metadata = writer.getDefaultImageMetadata(tipoImagem, param);
        configurarDpiJfif(metadata, dpi);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(imagem, null, metadata), param);
        } finally {
            writer.dispose();
        }

        return baos.toByteArray();
    }

    private void configurarDpiJfif(IIOMetadata metadata, int dpi) throws IOException {
        String formato = "javax_imageio_jpeg_image_1.0";
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(formato);
        IIOMetadataNode jpegVariety = obterOuCriarNo(root, "JPEGvariety");
        IIOMetadataNode jfif = obterOuCriarNo(jpegVariety, "app0JFIF");

        jfif.setAttribute("majorVersion", "1");
        jfif.setAttribute("minorVersion", "2");
        jfif.setAttribute("resUnits", "1");
        jfif.setAttribute("Xdensity", String.valueOf(dpi));
        jfif.setAttribute("Ydensity", String.valueOf(dpi));
        jfif.setAttribute("thumbWidth", "0");
        jfif.setAttribute("thumbHeight", "0");

        metadata.setFromTree(formato, root);
    }

    private IIOMetadataNode obterOuCriarNo(IIOMetadataNode pai, String nome) {
        for (int i = 0; i < pai.getLength(); i++) {
            if (nome.equals(pai.item(i).getNodeName())) {
                return (IIOMetadataNode) pai.item(i);
            }
        }

        IIOMetadataNode novoNo = new IIOMetadataNode(nome);
        pai.appendChild(novoNo);
        return novoNo;
    }

    private static void aplicarRenderizacaoAltaQualidade(Graphics2D g2d) {
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    private static double normalizarCropInicioRatio(double cropInicioRatio) {
        if (!Double.isFinite(cropInicioRatio) || cropInicioRatio < 0 || cropInicioRatio >= 1) {
            return CROP_INICIO_RATIO_PADRAO;
        }

        return cropInicioRatio;
    }

    private static double normalizarCropAlturaRatio(double cropAlturaRatio) {
        if (!Double.isFinite(cropAlturaRatio) || cropAlturaRatio <= 0 || cropAlturaRatio > 1) {
            return CROP_ALTURA_RATIO_PADRAO;
        }

        return cropAlturaRatio;
    }
}
