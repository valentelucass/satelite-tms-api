package com.example.satelite.services.vedacit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.CteDataDTO;
import com.example.satelite.dto.rodogarcia.CteItemDTO;
import com.example.satelite.dto.rodogarcia.CteResponseDTO;
import com.example.satelite.services.etl.EslRequestPolicyService;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocument;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocumentSource;

class VedacitCteCanhotoReconciliationServiceTest {
    private static final String NFE = "35260760642774001209550010002263491221491453";
    private static final String COMPLEMENTAR = "35260760960473000758570030000506151944330916";
    private static final String TRANSPORTE = "35260760960473000758570030000491761607851207";

    @Test
    void usaCteDoArquivoQuandoXmlComplementarReferenciaTransporte() {
        RodogarciaClient client = mock(RodogarciaClient.class);
        EslRequestPolicyService policy = mock(EslRequestPolicyService.class);
        VedacitSftpDocumentSource sftp = mock(VedacitSftpDocumentSource.class);
        when(sftp.buscarComprovantesPorNfe(NFE)).thenReturn(List.of(documento(TRANSPORTE)));
        when(policy.executarComTelemetria(any(), any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        when(client.buscarXmlCte(anyString(), anyString())).thenReturn(resposta(xmlComplementar()));

        var service = new VedacitCteCanhotoReconciliationService(client, policy, sftp, "token");
        var decisao = service.reconciliar(NFE, COMPLEMENTAR);

        assertTrue(decisao.encontrada());
        assertEquals(TRANSPORTE, decisao.chaveCteEfetiva());
        assertEquals("COMPLEMENTAR_PARA_TRANSPORTE", decisao.tipo());
    }

    @Test
    void mantemCorrelacaoExataSemConsultarEsl() {
        RodogarciaClient client = mock(RodogarciaClient.class);
        EslRequestPolicyService policy = mock(EslRequestPolicyService.class);
        VedacitSftpDocumentSource sftp = mock(VedacitSftpDocumentSource.class);
        when(sftp.buscarComprovantesPorNfe(NFE)).thenReturn(List.of(documento(COMPLEMENTAR)));

        var decisao = new VedacitCteCanhotoReconciliationService(client, policy, sftp, "token")
                .reconciliar(NFE, COMPLEMENTAR);

        assertTrue(decisao.encontrada());
        assertEquals("EXATO", decisao.tipo());
        verifyNoInteractions(client, policy);
    }

    @Test
    void mantemPendenteQuandoHaMaisDeUmArquivoSemVinculoExplicito() {
        RodogarciaClient client = mock(RodogarciaClient.class);
        EslRequestPolicyService policy = mock(EslRequestPolicyService.class);
        VedacitSftpDocumentSource sftp = mock(VedacitSftpDocumentSource.class);
        when(sftp.buscarComprovantesPorNfe(NFE)).thenReturn(List.of(documento(TRANSPORTE), documento(COMPLEMENTAR)));
        when(policy.executarComTelemetria(any(), any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        when(client.buscarXmlCte(anyString(), anyString())).thenReturn(resposta(xmlNormal()));

        var decisao = new VedacitCteCanhotoReconciliationService(client, policy, sftp, "token")
                .reconciliar(NFE, "35260760960473000758570030000599999999999999");

        assertFalse(decisao.encontrada());
        assertEquals("PENDENTE_RECONCILIACAO", decisao.tipo());
    }

    @Test
    void naoConsultaEslQuandoNaoHaArquivoSftpParaANfe() {
        RodogarciaClient client = mock(RodogarciaClient.class);
        EslRequestPolicyService policy = mock(EslRequestPolicyService.class);
        VedacitSftpDocumentSource sftp = mock(VedacitSftpDocumentSource.class);
        when(sftp.buscarComprovantesPorNfe(NFE)).thenReturn(List.of());

        var decisao = new VedacitCteCanhotoReconciliationService(client, policy, sftp, "token")
                .reconciliar(NFE, COMPLEMENTAR);

        assertFalse(decisao.encontrada());
        verifyNoInteractions(client, policy);
    }

    private VedacitSftpDocument documento(String cte) {
        return new VedacitSftpDocument(VedacitSftpDocument.Tipo.COMPROVANTE, "comprovantes/x.jpg", cte, NFE, 1, Instant.EPOCH, new byte[] { 1 });
    }

    private CteResponseDTO resposta(String xml) {
        return new CteResponseDTO(List.of(new CteDataDTO(new CteItemDTO(1L, "authorized", xml))));
    }

    private String xmlComplementar() {
        return "<CTe><infCte><ide><tpCTe>1</tpCTe></ide><infCteComp><chCTe>" + TRANSPORTE + "</chCTe></infCteComp></infCte></CTe>";
    }

    private String xmlNormal() {
        return "<CTe><infCte><ide><tpCTe>0</tpCTe></ide></infCte></CTe>";
    }
}
