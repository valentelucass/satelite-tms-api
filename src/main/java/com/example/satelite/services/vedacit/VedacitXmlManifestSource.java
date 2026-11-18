package com.example.satelite.services.vedacit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.satelite.utils.CteXmlValidator;

/** Ativada apenas por manifesto finito, explícito e verificado antes da execução. */
@Component
@ConditionalOnProperty(name = "vedacit.xml-local.manifest")
public final class VedacitXmlManifestSource implements VedacitXmlDocumentSource {
    private static final long LIMITE = 20L * 1024 * 1024;
    private final Path raiz;
    private final Map<String, Entrada> entradas;

    public VedacitXmlManifestSource(@Value("${vedacit.xml-local.manifest}") String manifesto) throws IOException {
        Path arquivo = Path.of(manifesto).toRealPath();
        raiz = arquivo.getParent();
        if (Files.size(arquivo) > 1024 * 1024) throw new IOException("Manifesto XML excede limite");
        Map<String, Entrada> alvos = new HashMap<>();
        for (String linha : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
            if (linha.isBlank()) continue;
            String[] p = linha.split("\t", -1);
            if (p.length != 5 || !p[0].matches("[0-9]{44}") || !p[1].matches("[0-9]{44}")
                    || !p[2].matches("[a-f0-9]{64}") || !"ESL_UI_DOWNLOAD".equals(p[3]))
                throw new IOException("Entrada de manifesto XML inválida");
            Path local = raiz.resolve(p[4]).normalize();
            if (!local.startsWith(raiz) || !local.toRealPath().startsWith(raiz)
                    || !Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Arquivo XML fora da raiz permitida");
            Entrada nova = new Entrada(local, p[2]);
            Entrada anterior = alvos.putIfAbsent(p[0] + ":" + p[1], nova);
            if (anterior != null && !anterior.equals(nova)) throw new IOException("Par XML ambíguo no manifesto");
        }
        if (alvos.isEmpty() || alvos.size() > 200) throw new IOException("Quantidade de alvos XML inválida");
        entradas = Map.copyOf(alvos);
    }

    @Override public Optional<Documento> buscar(String chaveCte, String chaveNfe) {
        Entrada entrada = entradas.get(chaveCte + ":" + chaveNfe);
        if (entrada == null) return Optional.empty();
        try {
            if (!Files.isRegularFile(entrada.local(), LinkOption.NOFOLLOW_LINKS)
                    || !entrada.local().toRealPath().startsWith(raiz)
                    || Files.size(entrada.local()) < 1 || Files.size(entrada.local()) > LIMITE)
                throw new IOException("Arquivo XML indisponível ou fora do limite");
            byte[] xml = Files.readAllBytes(entrada.local());
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(xml));
            if (!hash.equals(entrada.sha256()) || !CteXmlValidator.corresponde(xml, chaveCte, chaveNfe))
                throw new IOException("Integridade ou identidade do XML divergente");
            return Optional.of(new Documento(xml, hash, "ESL_UI_DOWNLOAD"));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("XML local recusado por validação", e);
        }
    }

    private record Entrada(Path local, String sha256) {}
}
