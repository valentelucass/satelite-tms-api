package com.example.satelite.services.origem.sftp.vedacit;

import java.util.Locale;
import java.util.Set;

/** Regras puras de caminho e nome; nunca permite sair da subpasta Vedacit configurada. */
public final class VedacitSftpPathPolicy {
    private static final Set<String> EXTENSOES_COMPROVANTE = Set.of("jpg", "jpeg", "jfif", "pdf");

    private VedacitSftpPathPolicy() { }

    public static String validarDiretorioCliente(String basePath, String clientPath) {
        String base = normalizarDiretorio(basePath, "Base SFTP ausente");
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
                + "\\.(?i:jpg|jpeg|jfif|pdf)");
    }

    public static boolean extensaoComprovanteAceita(String nome) {
        int ponto = nome == null ? -1 : nome.lastIndexOf('.');
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
