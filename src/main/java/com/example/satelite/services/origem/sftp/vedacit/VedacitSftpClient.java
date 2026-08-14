package com.example.satelite.services.origem.sftp.vedacit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
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
    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String basePath;
    private final String clientPath;
    private final String hostKeySha256;
    private final long maxFileSizeBytes;

    public VedacitSftpClient(
            @Value("${SFTP_RODOGARCIA_ENABLED:false}") boolean enabled,
            @Value("${SFTP_RODOGARCIA_HOST:}") String host,
            @Value("${SFTP_RODOGARCIA_PORT:22}") int port,
            @Value("${SFTP_RODOGARCIA_USERNAME:}") String username,
            @Value("${SFTP_RODOGARCIA_PASSWORD:}") String password,
            @Value("${SFTP_RODOGARCIA_BASE_PATH:}") String basePath,
            @Value("${SFTP_RODOGARCIA_CLIENT_PATH:}") String clientPath,
            @Value("${SFTP_RODOGARCIA_HOST_KEY_SHA256:}") String hostKeySha256,
            @Value("${SFTP_RODOGARCIA_MAX_FILE_SIZE_BYTES:26214400}") long maxFileSizeBytes
    ) {
        this.enabled = enabled; this.host = host; this.port = port; this.username = username; this.password = password;
        this.basePath = basePath; this.clientPath = VedacitSftpPathPolicy.validarDiretorioCliente(basePath, clientPath);
        this.hostKeySha256 = hostKeySha256; this.maxFileSizeBytes = maxFileSizeBytes;
    }

    @Override public Optional<VedacitSftpDocument> buscarXmlCte(String cte, String nfe) {
        return buscar(VedacitSftpPathPolicy.caminhoXml(basePath, clientPath), VedacitSftpDocument.Tipo.XML_CTE, cte, nfe, false);
    }
    @Override public Optional<VedacitSftpDocument> buscarComprovante(String cte, String nfe) {
        return buscar(VedacitSftpPathPolicy.caminhoComprovantes(basePath, clientPath), VedacitSftpDocument.Tipo.COMPROVANTE, cte, nfe, true);
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
    private void validarConfiguracao() {
        if (host.isBlank() || username.isBlank() || password.isBlank() || hostKeySha256.isBlank() || port <= 0 || maxFileSizeBytes <= 0) throw new IllegalStateException("Configuração SFTP Vedacit incompleta");
    }
}
