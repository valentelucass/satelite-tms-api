package com.example.satelite.services.vedacit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.services.etl.EslRequestPolicyService;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocument;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocumentSource;
import com.example.satelite.utils.ImageDownloader;

class VedacitXmlManifestSourceTest {
    private static final String CTE = "1".repeat(44);
    private static final String NFE = "2".repeat(44);
    @TempDir Path dir;

    private byte[] xml(String nfe) {
        return ("<CTe xmlns=\"http://www.portalfiscal.inf.br/cte\"><infCte Id=\"CTe" + CTE
                + "\"><infNFe><chave>" + nfe + "</chave></infNFe></infCte></CTe>")
                .getBytes(StandardCharsets.UTF_8);
    }
    private String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private VedacitXmlManifestSource source(byte[] bytes) throws Exception {
        Files.write(dir.resolve("official.xml"), bytes);
        Files.writeString(dir.resolve("manifest.tsv"), CTE + "\t" + NFE + "\t" + hash(bytes)
                + "\tESL_UI_DOWNLOAD\tofficial.xml\n");
        return new VedacitXmlManifestSource(dir.resolve("manifest.tsv").toString());
    }
    @Test void leSomenteParExplicitamenteManifestado() throws Exception {
        byte[] bytes = xml(NFE);
        var source = source(bytes);
        assertArrayEquals(bytes, source.buscar(CTE, NFE).orElseThrow().conteudo());
        assertTrue(source.buscar(CTE, "3".repeat(44)).isEmpty());
    }
    @Test void recusaArquivoAlteradoDepoisDaValidacao() throws Exception {
        var source = source(xml(NFE));
        Files.writeString(dir.resolve("official.xml"), "alterado");
        assertThrows(IllegalStateException.class, () -> source.buscar(CTE, NFE));
    }
    @Test void recusaNfeDivergenteMesmoComHashCorreto() throws Exception {
        var source = source(xml("3".repeat(44)));
        assertThrows(IllegalStateException.class, () -> source.buscar(CTE, NFE));
    }
    @Test void recusaCaminhoForaDaRaiz() throws Exception {
        Files.writeString(dir.resolve("manifest.tsv"), CTE + "\t" + NFE + "\t" + "a".repeat(64)
                + "\tESL_UI_DOWNLOAD\t../outside.xml\n");
        assertThrows(java.io.IOException.class,
                () -> new VedacitXmlManifestSource(dir.resolve("manifest.tsv").toString()));
    }
    @Test void documentoProtegeBytesContraAlteracaoExterna() throws Exception {
        byte[] bytes = xml(NFE);
        var document = source(bytes).buscar(CTE, NFE).orElseThrow();
        document.conteudo()[0] = 0;
        assertArrayEquals(bytes, document.conteudo());
    }
    private VedacitIntegrationService service(RodogarciaClient api, VedacitSftpDocumentSource sftp) {
        return new VedacitIntegrationService(mock(ImageDownloader.class), api,
                mock(EslRequestPolicyService.class), sftp);
    }
    @Test void fonteOficialFuncionaDuranteEsperaRestSemApagarCooldown() throws Exception {
        var api = mock(RodogarciaClient.class);
        var service = service(api, null);
        ReflectionTestUtils.setField(service, "xmlDocumentSource", source(xml(NFE)));
        ReflectionTestUtils.setField(service, "xmlEslLiberadoEm", Long.MAX_VALUE);
        byte[] bytes = ReflectionTestUtils.invokeMethod(service, "baixarXmlCte", CTE, NFE);
        assertArrayEquals(xml(NFE), bytes);
        assertEquals(Long.MAX_VALUE, ReflectionTestUtils.getField(service, "xmlEslLiberadoEm"));
        verifyNoInteractions(api);
    }
    @Test void ausenciaNoManifestoPreservaEsperaRest() throws Exception {
        var api = mock(RodogarciaClient.class);
        var service = service(api, null);
        ReflectionTestUtils.setField(service, "xmlDocumentSource", source(xml(NFE)));
        ReflectionTestUtils.setField(service, "xmlEslLiberadoEm", Long.MAX_VALUE);
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "baixarXmlCte", CTE, "3".repeat(44)));
        verifyNoInteractions(api);
    }
    @Test void recusaHashDeFonteAntesDeQualquerChamadaRest() throws Exception {
        var api = mock(RodogarciaClient.class);
        var service = service(api, null);
        VedacitXmlDocumentSource invalid = (cte, nfe) -> Optional.of(
                new VedacitXmlDocumentSource.Documento(xml(NFE), "a".repeat(64), "ESL_UI_DOWNLOAD"));
        ReflectionTestUtils.setField(service, "xmlDocumentSource", invalid);
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "baixarXmlCte", CTE, NFE));
        verifyNoInteractions(api);
    }
    @Test void sftpContinuaPrioritario() throws Exception {
        var api = mock(RodogarciaClient.class);
        var sftp = mock(VedacitSftpDocumentSource.class);
        var gui = mock(VedacitXmlDocumentSource.class);
        byte[] bytes = xml(NFE);
        when(sftp.buscarXmlCte(CTE, NFE)).thenReturn(Optional.of(new VedacitSftpDocument(
                VedacitSftpDocument.Tipo.XML_CTE, "local", CTE, NFE,
                bytes.length, java.time.Instant.now(), bytes)));
        var service = service(api, sftp);
        ReflectionTestUtils.setField(service, "xmlDocumentSource", gui);
        assertArrayEquals(bytes, (byte[]) ReflectionTestUtils.invokeMethod(service, "baixarXmlCte", CTE, NFE));
        verifyNoInteractions(gui, api);
    }
}
