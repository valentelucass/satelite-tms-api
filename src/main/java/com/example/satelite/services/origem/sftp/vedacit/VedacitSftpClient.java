package com.example.satelite.services.origem.sftp.vedacit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.FingerprintVerifier;

/** Cliente read-only exclusivo Vedacit. Não conhece ESL, SOAP ou auditoria. */
@Service
public class VedacitSftpClient implements VedacitSftpDocumentSource {
    private final String identificadorCliente;
    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String basePath;
    private final String clientPath;
    private final String hostKeySha256;
    private final long maxFileSizeBytes;
    private final long stableForMs;

    @Autowired
    public VedacitSftpClient(
            @Value("${SFTP_RODOGARCIA_ENABLED:false}") boolean enabled,
            @Value("${SFTP_RODOGARCIA_HOST:}") String host,
            @Value("${SFTP_RODOGARCIA_PORT:22}") int port,
            @Value("${SFTP_RODOGARCIA_USERNAME:}") String username,
            @Value("${SFTP_RODOGARCIA_PASSWORD:}") String password,
            @Value("${SFTP_RODOGARCIA_BASE_PATH:}") String basePath,
            @Value("${SFTP_RODOGARCIA_CLIENT_PATH:}") String clientPath,
            @Value("${SFTP_RODOGARCIA_HOST_KEY_SHA256:}") String hostKeySha256,
            @Value("${SFTP_RODOGARCIA_MAX_FILE_SIZE_BYTES:26214400}") long maxFileSizeBytes,
            @Value("${SFTP_RODOGARCIA_STABLE_FOR_MS:120000}") long stableForMs
    ) {
        this("LEGADO", enabled, host, port, username, password, basePath, clientPath, hostKeySha256, maxFileSizeBytes, stableForMs);
    }

    /** Cria uma conexão isolada para um único perfil do WORK-SFTP-CLIENTES. */
    public VedacitSftpClient(SftpClientesProperties.Perfil perfil) {
        this(
                perfil.identificador(), true, perfil.host(), perfil.porta(), perfil.usuario(), perfil.senha(),
                perfil.diretorioBase(), perfil.diretorioCliente(), perfil.hostKeySha256(),
                perfil.maxTamanhoArquivoBytes(), perfil.estabilidadeMinimaMs()
        );
    }

    private VedacitSftpClient(
            String identificadorCliente,
            boolean enabled,
            String host,
            int port,
            String username,
            String password,
            String basePath,
            String clientPath,
            String hostKeySha256,
            long maxFileSizeBytes,
            long stableForMs
    ) {
        this.identificadorCliente = identificadorCliente;
        this.enabled = enabled; this.host = host; this.port = port; this.username = username; this.password = password;
        this.basePath = basePath; this.clientPath = VedacitSftpPathPolicy.validarDiretorioCliente(basePath, clientPath);
        this.hostKeySha256 = hostKeySha256; this.maxFileSizeBytes = maxFileSizeBytes;
        this.stableForMs = stableForMs;
    }

    public String identificadorCliente() {
        return identificadorCliente;
    }

    @Override public Optional<VedacitSftpDocument> buscarXmlCte(String cte, String nfe) {
        return buscar(VedacitSftpPathPolicy.caminhoXml(basePath, clientPath), VedacitSftpDocument.Tipo.XML_CTE, cte, nfe, false);
    }
    @Override public Optional<VedacitSftpDocument> buscarComprovante(String cte, String nfe) {
        return buscar(VedacitSftpPathPolicy.caminhoComprovantes(basePath, clientPath), VedacitSftpDocument.Tipo.COMPROVANTE, cte, nfe, true);
    }

    @Override public List<VedacitSftpDocument> buscarComprovantesPorNfe(String nfe) {
        if (!enabled) return List.of();
        validarConfiguracao();
        if (nfe == null || !nfe.matches("\\d{44}")) throw new IllegalArgumentException("Chave NF-e SFTP inválida");
        String directory = VedacitSftpPathPolicy.caminhoComprovantes(basePath, clientPath);
        try (SSHClient ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(FingerprintVerifier.getInstance(hostKeySha256));
            ssh.connect(host, port); ssh.authPassword(username, password);
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                return sftp.ls(directory).stream()
                        .map(file -> Map.entry(file, VedacitSftpPathPolicy.extrairChavesComprovante(file.getName())))
                        .filter(entry -> entry.getValue().isPresent() && nfe.equals(entry.getValue().get().chaveNfe()))
                        .filter(entry -> entry.getKey().getAttributes().getSize() > 0 && entry.getKey().getAttributes().getSize() <= maxFileSizeBytes)
                        .map(entry -> criarDocumentoComprovante(sftp, directory, entry.getKey(), entry.getValue().get()))
                        .flatMap(documento -> documento == null ? Stream.empty() : documento.stream())
                        .toList();
            }
        } catch (IOException e) { throw new IllegalStateException("Falha controlada na leitura SFTP Vedacit", e); }
    }

    @Override public List<VedacitSftpDocument> listarComprovantes() {
        return listarInventarioComprovantes().documentosValidos();
    }

    /**
     * Lista metadados e expõe os arquivos que não podem integrar a fila ainda.
     * Nenhum conteúdo é baixado nesta etapa.
     */
    public VedacitSftpInventory listarInventarioComprovantes() {
        if (!enabled) return new VedacitSftpInventory(List.of(), List.of());
        validarConfiguracao();
        String directory = VedacitSftpPathPolicy.caminhoComprovantes(basePath, clientPath);
        try (SSHClient ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(FingerprintVerifier.getInstance(hostKeySha256));
            ssh.connect(host, port); ssh.authPassword(username, password);
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                List<VedacitSftpDocument> validos = new ArrayList<>();
                List<VedacitSftpInventory.DocumentoRejeitado> rejeitados = new ArrayList<>();
                Instant limiteEstabilidade = Instant.now().minusMillis(Math.max(0L, stableForMs));
                for (RemoteResourceInfo file : sftp.ls(directory)) {
                    String caminho = "comprovantes/" + file.getName();
                    var chaves = VedacitSftpPathPolicy.extrairChavesComprovante(file.getName());
                    long tamanho = file.getAttributes().getSize();
                    long mtime = file.getAttributes().getMtime();
                    if (chaves.isEmpty()) {
                        rejeitados.add(new VedacitSftpInventory.DocumentoRejeitado(caminho, null, null, "Arquivo inválido: nome sem NF-e/CT-e válidos"));
                    } else if (tamanho <= 0 || tamanho > maxFileSizeBytes) {
                        rejeitados.add(new VedacitSftpInventory.DocumentoRejeitado(caminho, chaves.get().chaveCte(), chaves.get().chaveNfe(), "Arquivo inválido: tamanho fora do limite configurado"));
                    } else if (Instant.ofEpochSecond(mtime).isAfter(limiteEstabilidade)) {
                        rejeitados.add(new VedacitSftpInventory.DocumentoRejeitado(caminho, chaves.get().chaveCte(), chaves.get().chaveNfe(), "Upload instável: arquivo ainda está na janela mínima de estabilidade"));
                    } else {
                        validos.add(new VedacitSftpDocument(VedacitSftpDocument.Tipo.COMPROVANTE, caminho,
                                chaves.get().chaveCte(), chaves.get().chaveNfe(), tamanho, Instant.ofEpochSecond(mtime), null));
                    }
                }
                return new VedacitSftpInventory(validos, rejeitados);
            }
        } catch (IOException e) { throw new IllegalStateException("Falha controlada ao listar SFTP Vedacit", e); }
    }

    /**
     * Confirma conectividade, autenticação e acesso à pasta de comprovantes
     * sem listar, baixar ou alterar qualquer documento remoto.
     */
    public void verificarDisponibilidade() {
        if (!enabled) return;
        validarConfiguracao();
        try (SSHClient ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(FingerprintVerifier.getInstance(hostKeySha256));
            ssh.connect(host, port);
            ssh.authPassword(username, password);
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                sftp.stat(VedacitSftpPathPolicy.caminhoComprovantes(basePath, clientPath));
            }
        } catch (IOException e) {
            throw new IllegalStateException("SFTP Vedacit indisponível para o lote: " + resumirCausa(e), e);
        }
    }

    private Optional<VedacitSftpDocument> buscar(String directory, VedacitSftpDocument.Tipo tipo, String cte, String nfe, boolean nomeDeterministico) {
        if (!enabled) return Optional.empty();
        validarConfiguracao();
        try (SSHClient ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(FingerprintVerifier.getInstance(hostKeySha256));
            ssh.connect(host, port); ssh.authPassword(username, password);
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                List<RemoteResourceInfo> files = sftp.ls(directory);
                for (RemoteResourceInfo file : files) {
                    String name = file.getName();
                    if (name.equals(".") || name.equals("..") || (nomeDeterministico && !VedacitSftpPathPolicy.nomeComprovanteCorresponde(name, cte, nfe))) continue;
                    if (tipo == VedacitSftpDocument.Tipo.XML_CTE && !name.toLowerCase().endsWith(".xml")) continue;
                    long size = file.getAttributes().getSize();
                    long mtime = file.getAttributes().getMtime();
                    if (size <= 0 || size > maxFileSizeBytes) continue;
                    byte[] bytes = read(sftp, directory + "/" + name, size);
                    if (bytes.length != size) continue;
                    var after = sftp.stat(directory + "/" + name);
                    if (after.getSize() != size || after.getMtime() != mtime) continue;
                    if (tipo == VedacitSftpDocument.Tipo.XML_CTE) {
                        String xml = new String(bytes, StandardCharsets.UTF_8);
                        if (!xml.contains(cte) || !xml.contains(nfe)) continue;
                    }
                    return Optional.of(new VedacitSftpDocument(tipo, tipo == VedacitSftpDocument.Tipo.XML_CTE ? "xml/" + name : "comprovantes/" + name, cte, nfe, size, Instant.ofEpochSecond(mtime), bytes));
                }
                return Optional.empty();
            }
        } catch (IOException e) { throw new IllegalStateException("Falha controlada na leitura SFTP Vedacit", e); }
    }
    private byte[] read(SFTPClient sftp, String path, long size) throws IOException {
        try (RemoteFile file = sftp.open(path, EnumSet.of(OpenMode.READ)); ByteArrayOutputStream output = new ByteArrayOutputStream((int) size)) {
            byte[] buffer = new byte[8192]; long offset = 0; int read;
            while ((read = file.read(offset, buffer, 0, buffer.length)) > 0) { output.write(buffer, 0, read); offset += read; }
            return output.toByteArray();
        }
    }

    private Optional<VedacitSftpDocument> criarDocumentoComprovante(
            SFTPClient sftp, String directory, RemoteResourceInfo file, VedacitSftpPathPolicy.ChavesComprovante chaves
    ) {
        try {
            long size = file.getAttributes().getSize(); long mtime = file.getAttributes().getMtime();
            byte[] bytes = read(sftp, directory + "/" + file.getName(), size);
            var after = sftp.stat(directory + "/" + file.getName());
            if (bytes.length != size || after.getSize() != size || after.getMtime() != mtime) return Optional.empty();
            return Optional.of(new VedacitSftpDocument(VedacitSftpDocument.Tipo.COMPROVANTE, "comprovantes/" + file.getName(), chaves.chaveCte(), chaves.chaveNfe(), size, Instant.ofEpochSecond(mtime), bytes));
        } catch (IOException e) { throw new IllegalStateException("Falha ao ler comprovante SFTP Vedacit", e); }
    }
    private void validarConfiguracao() {
        if (host.isBlank() || username.isBlank() || password.isBlank() || hostKeySha256.isBlank() || port <= 0 || maxFileSizeBytes <= 0) throw new IllegalStateException("Configuração SFTP Vedacit incompleta");
    }

    private String resumirCausa(IOException erro) {
        Throwable causa = erro;
        while (causa.getCause() != null) {
            causa = causa.getCause();
        }
        String mensagem = causa.getMessage();
        return causa.getClass().getSimpleName() + (mensagem == null || mensagem.isBlank() ? "" : ": " + mensagem);
    }
}
