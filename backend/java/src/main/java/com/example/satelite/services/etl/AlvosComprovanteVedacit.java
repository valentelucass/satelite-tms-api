package com.example.satelite.services.etl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Lista dirigida opcional: nunca amplia os alvos por NF-e ou por reconciliação. */
public final class AlvosComprovanteVedacit {
    public static final String PROPRIEDADE = "vedacit.sftp-receipt-batch.targets-file";
    private static final int MAXIMO_ALVOS = 100;
    private static final long MAXIMO_BYTES = 32 * 1024;
    private static final ThreadLocal<Alvo> ATUAL = new ThreadLocal<>();

    private AlvosComprovanteVedacit() { }

    public record Alvo(String chaveNfe, String chaveCte) {
        public Alvo {
            if (!chaveValida(chaveNfe, "55") || !chaveValida(chaveCte, "57")) {
                throw new IllegalArgumentException("Par de alvos inválido; conferir o arquivo local.");
            }
        }
    }

    private static boolean chaveValida(String chave, String modelo) {
        if (chave == null || !chave.matches("[0-9]{44}") || !modelo.equals(chave.substring(20, 22))) return false;
        int soma = 0;
        for (int i = 42, peso = 2; i >= 0; i--, peso = peso == 9 ? 2 : peso + 1) {
            soma += (chave.charAt(i) - '0') * peso;
        }
        int digito = 11 - soma % 11;
        return chave.charAt(43) - '0' == (digito >= 10 ? 0 : digito);
    }

    public static List<Alvo> carregar(String configurado) throws IOException {
        if (configurado == null || configurado.isBlank()) {
            throw new IllegalArgumentException("Arquivo de alvos configurado sem caminho.");
        }
        Path arquivo = Path.of(configurado.trim());
        if (!Files.isRegularFile(arquivo) || Files.size(arquivo) > MAXIMO_BYTES) {
            throw new IllegalArgumentException("Arquivo de alvos ausente ou acima do limite seguro.");
        }
        LinkedHashSet<Alvo> alvos = new LinkedHashSet<>();
        for (String original : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
            String linha = original.replace("\uFEFF", "").trim();
            if (linha.isBlank() || linha.startsWith("#")) continue;
            if (!linha.matches("[0-9]{44};[0-9]{44}")) {
                throw new IllegalArgumentException("Arquivo de alvos contém linha inválida.");
            }
            String[] par = linha.split(";", -1);
            alvos.add(new Alvo(par[0], par[1]));
            if (alvos.size() > MAXIMO_ALVOS) {
                throw new IllegalArgumentException("Arquivo de alvos acima do limite de documentos.");
            }
        }
        if (alvos.isEmpty()) throw new IllegalArgumentException("Arquivo de alvos vazio.");
        return List.copyOf(alvos);
    }

    public static Escopo restringir(Alvo alvo) {
        Objects.requireNonNull(alvo, "Alvo obrigatório.");
        Alvo anterior = ATUAL.get();
        ATUAL.set(alvo);
        return new Escopo(anterior);
    }

    public static void validarEnvio(String chaveNfe, String chaveCte) {
        Alvo alvo = ATUAL.get();
        if (alvo != null && (!alvo.chaveNfe().equals(chaveNfe) || !alvo.chaveCte().equals(chaveCte))) {
            throw new IllegalStateException("Envio bloqueado: documento fora do par dirigido.");
        }
    }

    public static final class Escopo implements AutoCloseable {
        private final Alvo anterior;
        private boolean fechado;
        private Escopo(Alvo anterior) { this.anterior = anterior; }
        @Override public void close() {
            if (fechado) return;
            fechado = true;
            if (anterior == null) ATUAL.remove(); else ATUAL.set(anterior);
        }
    }
}
