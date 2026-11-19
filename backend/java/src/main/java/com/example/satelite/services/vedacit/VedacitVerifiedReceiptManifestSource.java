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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocument;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocumentSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Entrada manual finita de comprovantes previamente baixados pela API cliente ESL.
 * Não é bean automático e não muda a origem, os toggles ou o perfil da produção.
 * Exige vínculo CT-e da resposta API e identificação explícita da NF-e no conteúdo.
 */
public final class VedacitVerifiedReceiptManifestSource implements VedacitSftpDocumentSource {
    private static final long MAX_DOCUMENT = 20L * 1024 * 1024;
    private static final long MAX_PROOF = 4L * 1024 * 1024;
    private static final String ORIGIN = "ESL_CLIENT_API_DOWNLOAD";
    private final Path root;
    private final Map<String, Entry> entries;

    public VedacitVerifiedReceiptManifestSource(String manifestPath) throws IOException {
        Path manifest = Path.of(manifestPath);
        if (Files.isSymbolicLink(manifest)) throw new IOException("Manifesto simbólico recusado");
        manifest = manifest.toRealPath();
        root = manifest.getParent();
        Map<?, ?> document = object(readJson(manifest, 1024 * 1024));
        if (!Integer.valueOf(1).equals(document.get("version")) || !ORIGIN.equals(document.get("origin")))
            throw new IOException("Origem ou versão do manifesto inválida");
        Path validation = safePath(text(document, "validation_path"));
        Path source = safePath(text(document, "source_api_path"));
        if (Files.size(validation)>MAX_PROOF || Files.size(source)>MAX_PROOF)
            throw new IOException("Prova documental excede limite");
        requireHash(validation, text(document, "validation_sha256"));
        requireHash(source, text(document, "source_api_sha256"));
        List<?> validations = list(readJson(validation, MAX_PROOF));
        List<?> apiRows = list(readJson(source, MAX_PROOF));
        List<?> targets = list(document.get("entries"));
        if (targets.isEmpty() || targets.size() > 200) throw new IOException("Quantidade de alvos inválida");
        Map<String, Entry> allowed = new HashMap<>();
        for (Object raw : targets) {
            Map<?, ?> target = object(raw);
            String nf = text(target, "nfe"), ct = text(target, "cte"), sha = text(target, "sha256");
            if (!validKey(nf, "55") || !validKey(ct, "57") || !sha.matches("[a-f0-9]{64}"))
                throw new IOException("Identidade do alvo inválida");
            Path file = safePath(text(target, "path"));
            if (!file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).matches(".+[.](jpg|jpeg|png|pdf)"))
                throw new IOException("Formato documental não permitido");
            requireDocument(file, sha);
            long validationMatches = validations.stream().map(VedacitVerifiedReceiptManifestSource::mapOrEmpty)
                    .filter(v -> nf.equals(v.get("nfe")) && ct.equals(v.get("cte")) && sha.equals(v.get("sha256")))
                    .filter(v -> yes(v,"source_cte_exact") && yes(v,"file_integrity_verified")
                            && yes(v,"full_image_decode_verified") && yes(v,"content_identity_confirmed")
                            && yes(v,"original_nfe_identified") && !yes(v,"barcode_conflict_requires_review")
                            && ORIGIN.equals(v.get("origin")) && receiptMarker(v))
                    .count();
            long sourceMatches = apiRows.stream().map(VedacitVerifiedReceiptManifestSource::mapOrEmpty)
                    .filter(v -> nf.equals(v.get("nfe")) && ct.equals(v.get("cte"))
                            && "PHOTO".equals(v.get("task")) && yes(v,"receipt_query_complete"))
                    .filter(v -> v.get("files_local") instanceof List<?> files && files.size()==1 && files.stream()
                            .map(VedacitVerifiedReceiptManifestSource::mapOrEmpty)
                            .anyMatch(f -> sha.equals(f.get("sha256")) && yes(f,"source_cte_exact")
                                    && f.get("source_receipt_id_local") != null && ORIGIN.equals(f.get("origin"))))
                    .count();
            if (validationMatches != 1 || sourceMatches != 1)
                throw new IOException("Prova de conteúdo ou vínculo API ausente, ambígua ou insuficiente");
            if (allowed.putIfAbsent(ct+":"+nf,new Entry(file,sha)) != null)
                throw new IOException("Alvo duplicado no manifesto");
        }
        entries = Map.copyOf(allowed);
    }

    @Override public String origemComprovante() { return "ESL_CLIENT_API_VERIFICADO"; }

    @Override public String referenciaComprovante(String ct,String nf) {
        Entry entry=entries.get(ct+":"+nf);
        return entry==null?null:"origem-validada/"+ORIGIN+"/"+entry.path().getFileName();
    }

    @Override public Optional<VedacitSftpDocument> buscarXmlCte(String ct, String nf) { return Optional.empty(); }

    @Override public Optional<VedacitSftpDocument> buscarComprovante(String ct, String nf) {
        Entry entry = entries.get(ct+":"+nf);
        if (entry == null) return Optional.empty();
        try {
            safePath(root.relativize(entry.path()).toString());
            requireDocument(entry.path(),entry.sha256());
            byte[] bytes = Files.readAllBytes(entry.path());
            if (!hash(bytes).equals(entry.sha256())) throw new IOException("Documento alterado durante a leitura");
            return Optional.of(new VedacitSftpDocument(VedacitSftpDocument.Tipo.COMPROVANTE,
                    "origem-validada/"+ORIGIN+"/"+entry.path().getFileName(),ct,nf,bytes.length,
                    Files.getLastModifiedTime(entry.path()).toInstant(),bytes));
        } catch (IOException ex) { throw new IllegalStateException("Comprovante manual recusado por validação",ex); }
    }

    private Path safePath(String relative) throws IOException {
        Path rel=Path.of(relative);
        if (rel.isAbsolute()) throw new IOException("Caminho absoluto recusado");
        Path candidate=root.resolve(rel).normalize();
        if (!candidate.startsWith(root)) throw new IOException("Caminho fora da raiz");
        Path walk=root;
        for (Path component:root.relativize(candidate)) {
            walk=walk.resolve(component);
            if (Files.isSymbolicLink(walk)) throw new IOException("Caminho simbólico recusado");
        }
        if (!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS) || !candidate.toRealPath().startsWith(root))
            throw new IOException("Arquivo indisponível na raiz permitida");
        return candidate;
    }
    private static void requireDocument(Path path,String sha) throws IOException {
        if (Files.size(path)<1 || Files.size(path)>MAX_DOCUMENT) throw new IOException("Tamanho documental inválido");
        requireHash(path,sha);
    }
    private static void requireHash(Path path,String expected) throws IOException {
        if (!expected.matches("[a-f0-9]{64}") || !hash(Files.readAllBytes(path)).equals(expected))
            throw new IOException("Hash de prova ou documento divergente");
    }
    private static Object readJson(Path path,long maximum) throws IOException {
        if (Files.size(path)<1 || Files.size(path)>maximum) throw new IOException("Arquivo de prova fora do limite");
        try { return new ObjectMapper().readValue(Files.readString(path,StandardCharsets.UTF_8),Object.class); }
        catch (RuntimeException ex) { throw new IOException("Prova JSON inválida",ex); }
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static boolean receiptMarker(Map<?,?> proof) {
        return proof.get("original_document_identity_facts") instanceof List<?> facts && facts.stream()
                .map(VedacitVerifiedReceiptManifestSource::mapOrEmpty).anyMatch(f->yes(f,"marcador_recebimento"));
    }
    private static boolean validKey(String key,String model) {
        if (!key.matches("[0-9]{44}") || !key.substring(20,22).equals(model)) return false;
        int sum=0;
        for(int i=42,weight=2;i>=0;i--,weight=weight==9?2:weight+1) sum+=(key.charAt(i)-'0')*weight;
        int digit=11-sum%11;
        return key.charAt(43)-'0'==(digit>=10?0:digit);
    }
    private static boolean yes(Map<?,?> value,String key) { return Boolean.TRUE.equals(value.get(key)); }
    private static String text(Map<?,?> value,String key) throws IOException {
        if (!(value.get(key) instanceof String text) || text.isBlank()) throw new IOException("Campo obrigatório ausente");
        return text;
    }
    private static Map<?,?> object(Object value) throws IOException {
        if (!(value instanceof Map<?,?> map)) throw new IOException("Objeto de manifesto inválido");
        return map;
    }
    private static List<?> list(Object value) throws IOException {
        if (!(value instanceof List<?> list)) throw new IOException("Lista de manifesto inválida");
        return list;
    }
    private static Map<?,?> mapOrEmpty(Object value) { return value instanceof Map<?,?> map ? map : Map.of(); }
    private record Entry(Path path,String sha256) { }
}
