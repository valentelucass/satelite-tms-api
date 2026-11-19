package com.example.satelite.services.vedacit;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class VedacitVerifiedReceiptManifestSourceTest {
    @TempDir Path dir;
    private final String nf=key("55"),ct=key("57");
    private Map<String,Object> validation,api,entry,manifest;
    private byte[] bytes;
    private String sha;
    @BeforeEach void prepare() throws Exception {
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB),"jpg",out);bytes=out.toByteArray();sha=hash(bytes);Files.write(dir.resolve("photo.jpg"),bytes);
        validation=new HashMap<>();validation.putAll(Map.of("nfe",nf,"cte",ct,"sha256",sha,"source_cte_exact",true,"file_integrity_verified",true,"full_image_decode_verified",true,"content_identity_confirmed",true,"original_nfe_identified",true,"origin","ESL_CLIENT_API_DOWNLOAD"));validation.put("barcode_conflict_requires_review",false);validation.put("original_document_identity_facts",List.of(Map.of("marcador_recebimento",true)));
        api=new HashMap<>(Map.of("nfe",nf,"cte",ct,"task","PHOTO","receipt_query_complete",true,"files_local",List.of(Map.of("sha256",sha,"source_cte_exact",true,"source_receipt_id_local",123L,"origin","ESL_CLIENT_API_DOWNLOAD"))));entry=new HashMap<>(Map.of("nfe",nf,"cte",ct,"sha256",sha,"path","photo.jpg"));manifest=new HashMap<>(Map.of("version",1,"origin","ESL_CLIENT_API_DOWNLOAD","validation_path","validation.json","source_api_path","api.json","entries",new ArrayList<>(List.of(entry))));
    }
    private VedacitVerifiedReceiptManifestSource source() throws Exception {
        var mapper=new ObjectMapper();Files.writeString(dir.resolve("validation.json"),mapper.writeValueAsString(List.of(validation)));Files.writeString(dir.resolve("api.json"),mapper.writeValueAsString(List.of(api)));manifest.put("validation_sha256",hash(Files.readAllBytes(dir.resolve("validation.json"))));manifest.put("source_api_sha256",hash(Files.readAllBytes(dir.resolve("api.json"))));Files.writeString(dir.resolve("manifest.json"),mapper.writeValueAsString(manifest));return new VedacitVerifiedReceiptManifestSource(dir.resolve("manifest.json").toString());
    }
    @Test void entregaSomenteDocumentoDoParRevisado() throws Exception {
        var source=source();assertArrayEquals(bytes,source.buscarComprovante(ct,nf).orElseThrow().conteudo());assertTrue(source.buscarComprovante(ct,key("55","9")).isEmpty());assertTrue(source.buscarXmlCte(ct,nf).isEmpty());assertEquals("ESL_CLIENT_API_VERIFICADO",source.origemComprovante());assertTrue(source.referenciaComprovante(ct,nf).contains("ESL_CLIENT_API_DOWNLOAD"));
    }
    @Test void recusaOrigemDeclaradaIncorretamente() {manifest.put("origin","SFTP");assertThrows(IOException.class,this::source);}
    @Test void recusaSemIdentificacaoNfeNoConteudo() {validation.put("original_nfe_identified",false);assertThrows(IOException.class,this::source);}
    @Test void recusaSemDecodificacaoIntegral() {validation.put("full_image_decode_verified",false);assertThrows(IOException.class,this::source);}
    @Test void recusaConflitoDeCodigoDeBarras() {validation.put("barcode_conflict_requires_review",true);assertThrows(IOException.class,this::source);}
    @Test void recusaSemMarcadorDeRecebimento() {validation.put("original_document_identity_facts",List.of());assertThrows(IOException.class,this::source);}
    @Test void recusaConsultaApiIncompleta() {api.put("receipt_query_complete",false);assertThrows(IOException.class,this::source);}
    @Test void recusaVinculoCteApiDiferente() {api.put("cte",key("57","9"));assertThrows(IOException.class,this::source);}
    @Test void recusaCaminhoForaDaRaiz() {entry.put("path","../outside.jpg");assertThrows(IOException.class,this::source);}
    @Test void recusaParDuplicado() {manifest.put("entries",List.of(entry,entry));assertThrows(IOException.class,this::source);}
    @Test void recusaMaisDeDuzentosAlvos() {manifest.put("entries",java.util.Collections.nCopies(201,entry));assertThrows(IOException.class,this::source);}
    @Test void recusaOrigemApiComMaisDeUmaImagem() {api.put("files_local",List.of(Map.of("sha256",sha,"source_cte_exact",true,"source_receipt_id_local",123L,"origin","ESL_CLIENT_API_DOWNLOAD"),Map.of("sha256","a".repeat(64),"source_cte_exact",true,"source_receipt_id_local",124L,"origin","ESL_CLIENT_API_DOWNLOAD")));assertThrows(IOException.class,this::source);}
    @Test void recusaChaveComDigitoInvalido() {entry.put("nfe",nf.substring(0,43)+(nf.endsWith("0")?"1":"0"));assertThrows(IOException.class,this::source);}
    @Test void recusaDocumentoModificadoAposAprovacao() throws Exception {var source=source();Files.writeString(dir.resolve("photo.jpg"),"different");assertThrows(IllegalStateException.class,()->source.buscarComprovante(ct,nf));}
    @Test void recusaHashDeProvaModificado() throws Exception {source();Files.writeString(dir.resolve("api.json"),"[]");assertThrows(IOException.class,()->new VedacitVerifiedReceiptManifestSource(dir.resolve("manifest.json").toString()));}
    private static String key(String model) {return key(model,"1");}
    private static String key(String model,String last) {
        String base="35261012345678000123"+model+"001"+"000000001"+"1"+"0000000"+last;int sum=0;
        for(int i=42,w=2;i>=0;i--,w=w==9?2:w+1)sum+=(base.charAt(i)-'0')*w;int d=11-sum%11;return base+(d>=10?0:d);
    }
    private static String hash(byte[] b) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
}
