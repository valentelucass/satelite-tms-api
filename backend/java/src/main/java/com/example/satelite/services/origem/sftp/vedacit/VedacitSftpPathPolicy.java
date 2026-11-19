package com.example.satelite.services.origem.sftp.vedacit;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Regras puras de caminho e nome; nunca permite sair da subpasta Vedacit configurada. */
public final class VedacitSftpPathPolicy {
    private static final Set<String> EXTENSOES_COMPROVANTE = Set.of("jpg", "jpeg", "jfif", "png", "pdf");

    private VedacitSftpPathPolicy() { }

    public static String validarDiretorioBase(String basePath) {
        return normalizarDiretorio(basePath, "Base SFTP ausente");
    }

    public static String validarDiretorioCliente(String basePath, String clientPath) {
        String base = validarDiretorioBase(basePath);
        String client = normalizarDiretorio(clientPath, "Subpasta SFTP Vedacit ausente");
        if (!client.startsWith(base + "/")) {
            throw new IllegalArgumentException("Subpasta SFTP Vedacit fora da base autorizada");
        }
        return client;
    }

    public static String caminhoXml(String basePath, String clientPath) {
        return validarDiretorioCliente(basePath, clientPath) + "/xml";
    }

    public static String caminhoComprovantes(String basePath, String clientPath) {
        return validarDiretorioCliente(basePath, clientPath) + "/comprovantes";
    }

    public static boolean nomeComprovanteCorresponde(String nome, String chaveCte, String chaveNfe) {
        return nome != null && nome.matches("\\d+_" + chaveValida(chaveCte) + "_" + chaveValida(chaveNfe)
                + "\\.(?i:jpg|jpeg|jfif|png|pdf)");
    }

    public static Optional<ChavesComprovante> extrairChavesComprovante(String nome) {
        if (nome == null) return Optional.empty();
        String nomeSeguro = nome;
        Matcher matcher = Pattern.compile("^\\d+_(\\d{44})_(\\d{44})\\.(?i:jpg|jpeg|jfif|png|pdf)$").matcher(nomeSeguro);
        return matcher.matches() ? Optional.of(new ChavesComprovante(matcher.group(1), matcher.group(2))) : Optional.empty();
    }

    public record ChavesComprovante(String chaveCte, String chaveNfe) { }

    public static boolean extensaoComprovanteAceita(String nome) {
        if (nome == null) return false;
        int ponto = nome.lastIndexOf('.');
        return ponto > 0 && EXTENSOES_COMPROVANTE.contains(nome.substring(ponto + 1).toLowerCase(Locale.ROOT));
    }

    private static String chaveValida(String chave) {
        if (chave == null || !chave.matches("\\d{44}")) {
            throw new IllegalArgumentException("Chave documental SFTP inválida");
        }
        return chave;
    }

    private static String normalizarDiretorio(String valor, String mensagem) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(mensagem);
        }
        String normalizado = valor.trim().replace('\\', '/').replaceAll("/+$", "");
        if (!normalizado.startsWith("/") || normalizado.contains("..") || normalizado.equals("/")) {
            throw new IllegalArgumentException("Caminho SFTP inválido");
        }
        return normalizado;
    }
}
