package com.example.satelite.services.origem.sftp.vedacit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.*;

class VedacitSftpClientSafetyTest {
    private static final String CTE = "1".repeat(44);
    private static final String NFE = "2".repeat(44);
    private static final String NAME = "123_" + CTE + "_" + NFE + ".jpg";
    private static final String ROOT = "/base/cliente";
    private static final String POD = ROOT + "/comprovantes/" + NAME;
    private static final long OLD = Instant.now().minusSeconds(3600).getEpochSecond();
    private final SFTPClient sftp = mock(SFTPClient.class);
    private final RemoteFile remote = mock(RemoteFile.class);
    private final VedacitSftpClient client = new VedacitSftpClient(true, "example.invalid", 22,
            "unit-user", "unit-password", "/base", ROOT,
            "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 1024, 120000);

    @BeforeEach
    void directoriesAreRegularDirectories() throws IOException {
        when(sftp.lstat(anyString())).thenReturn(attrs(FileMode.Type.DIRECTORY, 0, OLD));
    }

    @Test
    void inventoryNeverDownloadsAndAuditsInvalidNamesSizesAndUnstableUploads() throws Exception {
        when(sftp.ls(ROOT + "/comprovantes")).thenReturn(List.of(
                file(NAME, FileMode.Type.REGULAR, 3, OLD),
                file("invalido.jpg", FileMode.Type.REGULAR, 3, OLD),
                file(NAME, FileMode.Type.REGULAR, 0, OLD),
                file(NAME, FileMode.Type.REGULAR, 2048, OLD),
                file(NAME, FileMode.Type.REGULAR, 3, Instant.now().getEpochSecond())));
        try (var construction = connection()) {
            var inventory = client.listarInventarioComprovantes();
            assertEquals(1, inventory.documentosValidos().size());
            assertEquals(4, inventory.rejeitados().size());
            assertNull(inventory.documentosValidos().get(0).conteudo());
            verify(sftp, never()).open(anyString(), any());
            verify(sftp).close();
            verify(construction.constructed().get(0)).close();
        }
    }

    @ParameterizedTest
    @EnumSource(value = FileMode.Type.class, names = {"SYMLINK", "DIRECTORY"})
    void inventoryRejectsNonRegularFilesWithValidDocumentNames(FileMode.Type type) throws Exception {
        when(sftp.ls(ROOT + "/comprovantes")).thenReturn(List.of(file(NAME, type, 3, OLD)));
        try (var ignored = connection()) {
            var inventory = client.listarInventarioComprovantes();
            assertTrue(inventory.documentosValidos().isEmpty());
            assertEquals(1, inventory.rejeitados().size());
        }
    }

    @Test
    void downloadDoesNotFollowDocumentSymlink() throws Exception {
        when(sftp.ls(ROOT + "/comprovantes")).thenReturn(List.of(file(NAME, FileMode.Type.SYMLINK, 3, OLD)));
        try (var ignored = connection()) {
            assertTrue(client.buscarComprovante(CTE, NFE).isEmpty());
            verify(sftp, never()).open(anyString(), any());
        }
    }

    @Test
    void refusesSymlinkInConfiguredDirectory() throws Exception {
        when(sftp.lstat(ROOT + "/comprovantes")).thenReturn(attrs(FileMode.Type.SYMLINK, 3, OLD));
        try (var ignored = connection()) {
            assertThrows(IllegalStateException.class, client::listarInventarioComprovantes);
            verify(sftp, never()).ls(anyString());
        }
    }

    @Test
    void directReceiptLookupRespectsUploadWindow() throws Exception {
        when(sftp.ls(ROOT + "/comprovantes")).thenReturn(List.of(file(NAME, FileMode.Type.REGULAR, 3, Instant.now().getEpochSecond())));
        try (var ignored = connection()) {
            assertTrue(client.buscarComprovante(CTE, NFE).isEmpty());
            verify(sftp, never()).open(anyString(), any());
        }
    }

    @Test
    void invoiceLookupRespectsUploadWindow() throws Exception {
        when(sftp.ls(ROOT + "/comprovantes")).thenReturn(List.of(file(NAME, FileMode.Type.REGULAR, 3, Instant.now().getEpochSecond())));
        try (var ignored = connection()) {
            assertTrue(client.buscarComprovantesPorNfe(NFE).isEmpty());
            verify(sftp, never()).open(anyString(), any());
        }
    }

    @Test
    void downloadsMatchingStableReceiptReadOnly() throws Exception {
        prepare(NAME, ROOT + "/comprovantes", new byte[]{1, 2, 3});
        try (var ignored = connection()) {
            var document = client.buscarComprovante(CTE, NFE).orElseThrow();
            assertArrayEquals(new byte[]{1, 2, 3}, document.conteudo());
            assertEquals("comprovantes/" + NAME, document.caminhoRelativo());
            verify(sftp).open(POD, EnumSet.of(OpenMode.READ));
            verify(remote).close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsFileChangedDuringDownload(boolean changedSize) throws Exception {
        prepare(NAME, ROOT + "/comprovantes", new byte[]{1, 2, 3});
        var changed = attrs(FileMode.Type.REGULAR, changedSize ? 4 : 3, changedSize ? OLD : OLD + 1);
        when(sftp.stat(POD)).thenReturn(changed);
        when(sftp.lstat(POD)).thenReturn(attrs(FileMode.Type.REGULAR, 3, OLD), changed);
        try (var ignored = connection()) {
            assertTrue(client.buscarComprovante(CTE, NFE).isEmpty());
        }
    }

    @Test
    void stopsGrowingDownloadAtAdvertisedSize() throws Exception {
        prepare(NAME, ROOT + "/comprovantes", new byte[]{1, 2, 3});
        when(remote.read(anyLong(), any(byte[].class), anyInt(), anyInt())).thenReturn(4, 4, 4, -1);
        try (var ignored = connection()) {
            assertThrows(IllegalStateException.class, () -> client.buscarComprovante(CTE, NFE));
            verify(remote, times(1)).read(anyLong(), any(byte[].class), anyInt(), anyInt());
            verify(remote).close();
        }
    }

    @Test
    void rejectsTruncatedDownload() throws Exception {
        prepare(NAME, ROOT + "/comprovantes", new byte[]{1, 2, 3});
        when(remote.read(anyLong(), any(byte[].class), anyInt(), anyInt())).thenReturn(2, -1);
        try (var ignored = connection()) {
            assertTrue(client.buscarComprovante(CTE, NFE).isEmpty());
        }
    }

    @Test
    void rejectsXmlWithKeysOnlyInComment() throws Exception {
        prepare("sample.xml", ROOT + "/xml", ("<outro><!-- " + CTE + " " + NFE + " --></outro>").getBytes(StandardCharsets.UTF_8));
        try (var ignored = connection()) {
            assertTrue(client.buscarXmlCte(CTE, NFE).isEmpty());
        }
    }

    @Test
    void readsXmlWhoseFiscalIdentifiersActuallyMatch() throws Exception {
        String xml = "<cteProc xmlns=\"http://www.portalfiscal.inf.br/cte\"><CTe><infCte Id=\"CTe" + CTE
                + "\"><infCTeNorm><infDoc><infNFe><chave>" + NFE
                + "</chave></infNFe></infDoc></infCTeNorm></infCte></CTe></cteProc>";
        prepare("sample.xml", ROOT + "/xml", xml.getBytes(StandardCharsets.UTF_8));
        try (var ignored = connection()) {
            assertTrue(client.buscarXmlCte(CTE, NFE).isPresent());
        }
    }

    @Test
    void wrapsConnectionFailureWithoutContinuingListing() throws Exception {
        try (var ignored = mockConstruction(SSHClient.class, (ssh, context) -> {
            doThrow(new IOException("unit connection failure")).when(ssh).connect(anyString(), anyInt());
        })) {
            assertThrows(IllegalStateException.class, client::verificarDisponibilidade);
            verify(sftp, never()).ls(anyString());
        }
    }

    @Test
    void xmlIndexadoEvitaRelerOutroCteEInvalidaAoMudarMetadados() throws Exception {
        byte[] xml = ("<CTe xmlns='http://www.portalfiscal.inf.br/cte'><infCte Id='CTe" + CTE
                + "'><infNFe><chave>" + NFE + "</chave></infNFe></infCte></CTe>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        prepare("a.xml", ROOT + "/xml", xml);
        String outro = "9".repeat(44);
        try (var ignored = connection()) {
            assertTrue(client.buscarXmlCte(CTE, NFE).isPresent());
            assertTrue(client.buscarXmlCte(outro, NFE).isEmpty());
            assertTrue(client.buscarXmlCte(outro, NFE).isEmpty());
            verify(sftp, times(1)).open(anyString(), any());
            when(sftp.ls(ROOT + "/xml")).thenReturn(List.of(file("a.xml", FileMode.Type.REGULAR, xml.length, OLD - 1)));
            when(sftp.lstat(ROOT + "/xml/a.xml")).thenReturn(attrs(FileMode.Type.REGULAR, xml.length, OLD - 1));
            assertTrue(client.buscarXmlCte(outro, NFE).isEmpty());
            verify(sftp, times(2)).open(anyString(), any());
        }
    }

    private MockedConstruction<SSHClient> connection() {
        return mockConstruction(SSHClient.class, (ssh, context) -> when(ssh.newSFTPClient()).thenReturn(sftp));
    }

    private static FileAttributes attrs(FileMode.Type type, long size, long mtime) {
        return new FileAttributes.Builder().withType(type).withSize(size).withAtimeMtime(mtime, mtime).build();
    }

    private static RemoteResourceInfo file(String name, FileMode.Type type, long size, long mtime) {
        return new RemoteResourceInfo(new PathComponents(ROOT, name, "/"), attrs(type, size, mtime));
    }

    private void prepare(String name, String directory, byte[] bytes) throws IOException {
        String path = directory + "/" + name;
        when(sftp.ls(directory)).thenReturn(List.of(file(name, FileMode.Type.REGULAR, bytes.length, OLD)));
        when(sftp.stat(path)).thenReturn(attrs(FileMode.Type.REGULAR, bytes.length, OLD));
        when(sftp.lstat(path)).thenReturn(attrs(FileMode.Type.REGULAR, bytes.length, OLD));
        when(sftp.open(path, EnumSet.of(OpenMode.READ))).thenReturn(remote);
        when(remote.read(anyLong(), any(byte[].class), anyInt(), anyInt())).thenAnswer(invocation -> {
            long offset = invocation.getArgument(0);
            if (offset >= bytes.length) return -1;
            byte[] dest = invocation.getArgument(1);
            int length = Math.min(bytes.length - (int) offset, invocation.getArgument(3));
            System.arraycopy(bytes, (int) offset, dest, invocation.getArgument(2), length);
            return length;
        });
    }
}
