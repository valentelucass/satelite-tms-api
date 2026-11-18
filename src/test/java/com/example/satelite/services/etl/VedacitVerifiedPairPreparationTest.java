package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.IntegracaoEventoRepository;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocument;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocumentSource;
import com.example.satelite.services.vedacit.VedacitDataEntregaService;

class VedacitVerifiedPairPreparationTest {
    private final String nf=key("55","1"),ct=key("57","1");
    private final LocalDateTime now=LocalDateTime.of(2026,10,30,12,0);
    private LogIntegracaoRepository repository; private EtlEstadoIntegracaoService state;
    private VedacitDataEntregaService delivery; private SftpDocumentoLockService locks;
    private IntegracaoEventoRepository events; private VedacitSftpDocumentSource source;
    private VedacitVerifiedPairPreparation preparation; private LogIntegracaoModel proof;
    private byte[] xml;
    @BeforeEach void prepare() {
        repository=mock(LogIntegracaoRepository.class);state=mock(EtlEstadoIntegracaoService.class);
        delivery=mock(VedacitDataEntregaService.class);locks=mock(SftpDocumentoLockService.class);
        events=mock(IntegracaoEventoRepository.class);source=mock(VedacitSftpDocumentSource.class);
        preparation=new VedacitVerifiedPairPreparation(repository,state,delivery,locks,events);
        proof=LogIntegracaoModel.builder().id(10L).sistemaDestino("VEDACIT").chaveCte(ct)
                .statusDados("SUCESSO").dataProcessamentoDados(now.minusDays(2)).build();
        xml=("<cteProc xmlns='http://www.portalfiscal.inf.br/cte'><CTe><infCte Id='CTe"+ct
                +"'><infCTeNorm><infDoc><infNFe><chave>"+nf+"</chave></infNFe></infDoc></infCTeNorm></infCte></CTe></cteProc>")
                .getBytes(StandardCharsets.UTF_8);
        when(locks.executarComLock(anyString(),anyString(),anyString(),any())).thenAnswer(i -> Optional.of(((Supplier<?>)i.getArgument(3)).get()));
        when(state.agoraAuditoria()).thenReturn(now);when(repository.findAtivosVedacitPorParExato(eq(nf),eq(ct),any())).thenReturn(List.of());
        when(repository.findXmlVedacitConfirmadoDatado(eq(ct),any())).thenReturn(List.of(proof));
        when(source.buscarComprovante(ct,nf)).thenReturn(Optional.of(new VedacitSftpDocument(VedacitSftpDocument.Tipo.COMPROVANTE,"photo.jpg",ct,nf,3,Instant.EPOCH,new byte[]{1,2,3})));
        when(source.origemComprovante()).thenReturn("ESL_CLIENT_API_VERIFICADO");when(source.referenciaComprovante(ct,nf)).thenReturn("origem-validada/photo.jpg");
        when(delivery.resolver(nf,ct)).thenReturn(VedacitDataEntregaService.Resolucao.encontrada(OffsetDateTime.parse("2026-10-25T14:30:00-03:00"),77L));
        when(state.salvarConciliacaoXmlSemEnvio(any(),anyLong(),anyLong())).thenAnswer(i -> {LogIntegracaoModel r=i.getArgument(0);if(r.getId()==null)r.setId(600L);return r;});
    }
    private VedacitVerifiedPairPreparation.Resultado run() {return preparation.preparar(new AlvosComprovanteVedacit.Alvo(nf,ct),xml,source);}
    private void unsaved() {verify(state,never()).salvarConciliacaoXmlSemEnvio(any(),any(),any());}
    @Test void constituiAuditoriaAPartirDeEntregaRealEXmlDoParJaConfirmado() {
        var result=run();assertEquals("CRIADO_COM_PROVAS_EXATAS",result.categoria());assertEquals(10L,result.logFonteXml());
        var capture=org.mockito.ArgumentCaptor.forClass(LogIntegracaoModel.class);verify(state).salvarConciliacaoXmlSemEnvio(capture.capture(),eq(10L),eq(77L));
        var r=capture.getValue();assertEquals(77L,r.getOccurrenceId());assertEquals("SUCESSO",r.getStatusDados());assertEquals(proof.getDataProcessamentoDados(),r.getDataProcessamentoDados());
        assertEquals("PENDENTE_FOTO",r.getStatusCanhoto());assertNull(r.getDataProcessamentoCanhoto());assertEquals(0,r.getTentativasDados());assertEquals(0,r.getTentativasCanhoto());assertEquals("ESL_CLIENT_API_VERIFICADO",r.getCanhotoOrigem());
    }
    @Test void recusaXmlDeOutraNotaMesmoComConfirmacaoGlobal() {xml=new String(xml,StandardCharsets.UTF_8).replace(nf,key("55","9")).getBytes(StandardCharsets.UTF_8);assertEquals("XML_NAO_COMPROVA_PAR_EXATO",run().categoria());unsaved();verifyNoInteractions(delivery);}
    @Test void recusaXmlDeOutroCte() {xml=new String(xml,StandardCharsets.UTF_8).replace(ct,key("57","9")).getBytes(StandardCharsets.UTF_8);assertEquals("XML_NAO_COMPROVA_PAR_EXATO",run().categoria());unsaved();}
    @Test void exigeConfirmacaoXmlDatada() {when(repository.findXmlVedacitConfirmadoDatado(eq(ct),any())).thenReturn(List.of());assertEquals("XML_SEM_CONFIRMACAO_DATADA",run().categoria());unsaved();}
    @Test void recusaStatusGenericoEnviadoComoProvaXml() {proof.setStatusDados("ENVIADO");assertEquals("PROVA_XML_INCONSISTENTE",run().categoria());unsaved();}
    @Test void recusaProvaXmlSemData() {proof.setDataProcessamentoDados(null);assertEquals("PROVA_XML_INCONSISTENTE",run().categoria());unsaved();}
    @Test void recusaProvaXmlComOutraIdentidade() {proof.setChaveCte(key("57","9"));assertEquals("PROVA_XML_INCONSISTENTE",run().categoria());unsaved();}
    @Test void recusaProvaXmlNoFuturo() {proof.setDataProcessamentoDados(now.plusMinutes(1));assertEquals("PROVA_XML_INCONSISTENTE",run().categoria());unsaved();}
    @Test void exigeEntregaRealCompletaEIdentificada() {when(delivery.resolver(nf,ct)).thenReturn(VedacitDataEntregaService.Resolucao.incompleta("SEM_EVENTO_REAL"));assertEquals("ENTREGA_REAL_NAO_CONFIRMADA",run().categoria());unsaved();}
    @Test void exigeIdDoEventoReal() {when(delivery.resolver(nf,ct)).thenReturn(VedacitDataEntregaService.Resolucao.encontrada(OffsetDateTime.now(),null));assertEquals("ENTREGA_REAL_NAO_CONFIRMADA",run().categoria());unsaved();}
    @Test void exigeDocumentoAtualDoMesmoPar() {when(source.buscarComprovante(ct,nf)).thenReturn(Optional.empty());assertEquals("FONTE_COMPROVANTE_INDISPONIVEL",run().categoria());unsaved();}
    @Test void naoConstituiParComInicioDeEnvioAnterior() {when(events.existeInicioEnvio("COMPROVANTE","POD:"+nf+":"+ct,null)).thenReturn(true);assertEquals("HISTORICO_COMPROVANTE_PROTEGIDO",run().categoria());unsaved();}
    @Test void preservaSucessoComprovanteAnterior() {when(state.canhotoVedacitSucessoRegistradoPorPar(nf,ct)).thenReturn(true);assertEquals("COMPROVANTE_JA_CONFIRMADO",run().categoria());unsaved();}
    @Test void preservaRetencaoDoPar() {when(state.canhotoVedacitRetidoPorPar(nf,ct)).thenReturn(true);assertEquals("HISTORICO_COMPROVANTE_PROTEGIDO",run().categoria());unsaved();}
    @Test void recusaAuditoriasAtivasDuplicadas() {when(repository.findAtivosVedacitPorParExato(eq(nf),eq(ct),any())).thenReturn(List.of(proof,proof));assertEquals("AUDITORIA_ATIVA_NAO_UNICA",run().categoria());unsaved();}
    private LogIntegracaoModel existing() {
        var current=LogIntegracaoModel.builder().id(42L).sistemaDestino("VEDACIT").chaveNfe(nf).chaveCte(ct).statusDados("PENDENTE_ORIGEM")
                .statusCanhoto("PENDENTE_FOTO").dataProcessamentoDados(now.minusDays(5)).tentativasDados(3).tentativasCanhoto(0)
                .mensagemErroDados("ORIGEM_XML_HTTP_401").dadosProximaAvaliacaoEm(now.plusHours(1)).build();
        when(repository.findAtivosVedacitPorParExato(eq(nf),eq(ct),any())).thenReturn(List.of(current));return current;
    }
    @Test void conciliaXmlExatoPreservandoTentativasEHistoricoDeComprovante() {var r=existing();var first=r.getDataProcessamentoCanhoto();var deadline=r.getDadosProximaAvaliacaoEm();assertEquals("XML_CONCILIADO_SEM_ENVIO",run().categoria());assertEquals(3,r.getTentativasDados());assertEquals(0,r.getTentativasCanhoto());assertEquals(first,r.getDataProcessamentoCanhoto());assertEquals(deadline,r.getDadosProximaAvaliacaoEm());assertEquals("ORIGEM_XML_HTTP_401",r.getMensagemErroDados());assertEquals(proof.getDataProcessamentoDados(),r.getDataProcessamentoDados());}
    @Test void preservaPrimeiraDataDeXmlJaConfirmado() {var r=existing();r.setStatusDados("SUCESSO");var date=r.getDataProcessamentoDados();assertEquals("JA_PREPARADO",run().categoria());assertEquals(date,r.getDataProcessamentoDados());unsaved();}
    @Test void preservaIncertezaDeSucessoXmlSemData() {var r=existing();r.setStatusDados("SUCESSO");r.setDataProcessamentoDados(null);assertEquals("SUCESSO_SEM_DATA_PRESERVADO",run().categoria());unsaved();}
    @Test void naoAlteraPrazoFuturoDeComprovante() {var r=existing();r.setCanhotoProximaAvaliacaoEm(now.plusHours(1));assertEquals("ESTADO_EXISTENTE_PROTEGIDO",run().categoria());unsaved();}
    @Test void naoAlteraRegistroComTentativaAnteriorDeComprovante() {existing().setTentativasCanhoto(1);assertEquals("ESTADO_EXISTENTE_PROTEGIDO",run().categoria());unsaved();}
    @Test void naoAlteraChaveEfetivaDiferente() {existing().setCanhotoChaveCteEfetiva(key("57","9"));assertEquals("ESTADO_EXISTENTE_PROTEGIDO",run().categoria());unsaved();}
    @Test void naoAlteraRejeicaoAnteriorDeXml() {var r=existing();r.setStatusDados("ERRO_DESTINO");r.setMensagemErroDados("SOAP_HTTP_502");assertEquals("ESTADO_EXISTENTE_PROTEGIDO",run().categoria());unsaved();}
    @Test void conciliaRecusaControladaDeOrigemLegadaSemReenviarXml() {var r=existing();r.setStatusDados("ERRO_DESTINO");r.setMensagemErroDados("ORIGEM_XML_AUTENTICACAO_EM_ESPERA");assertEquals("XML_CONCILIADO_SEM_ENVIO",run().categoria());assertEquals(3,r.getTentativasDados());assertEquals("ORIGEM_XML_AUTENTICACAO_EM_ESPERA",r.getMensagemErroDados());assertNull(r.getDataProcessamentoCanhoto());}
    @Test void recusaOrigem503QueNaoComprovaAusenciaDeEnvio() {var r=existing();r.setStatusDados("ERRO_DESTINO");r.setMensagemErroDados("ORIGEM_XML_HTTP_503");assertEquals("ESTADO_EXISTENTE_PROTEGIDO",run().categoria());unsaved();}
    @Test void preservaSoapAnteriorEmAndamento() {var r=existing();r.setStatusDados("ERRO_DESTINO");r.setMensagemErroDados("SOAP_ANTERIOR_EM_ANDAMENTO");assertEquals("ESTADO_EXISTENTE_PROTEGIDO",run().categoria());unsaved();}
    @Test void recusaOrigemLegadaComInicioRemotoDoMesmoLog() {var r=existing();r.setStatusDados("ERRO_DESTINO");r.setMensagemErroDados("ORIGEM_XML_AUTENTICACAO_EM_ESPERA");when(events.existeInicioEnvio("DADOS","CTE:"+ct,42L)).thenReturn(true);assertEquals("ESTADO_EXISTENTE_PROTEGIDO",run().categoria());unsaved();}
    @Test void naoAlteraInicioRemotoAnteriorDoMesmoLogXml() {existing();when(events.existeInicioEnvio("DADOS","CTE:"+ct,42L)).thenReturn(true);assertEquals("ESTADO_EXISTENTE_PROTEGIDO",run().categoria());unsaved();}
    @Test void recusaQuandoLockIndisponivel() {doReturn(Optional.empty()).when(locks).executarComLock(anyString(),anyString(),anyString(),any());assertEquals("LOCK_XML_INDISPONIVEL",run().categoria());unsaved();}
    @Test void respeitaEscopoDirigido() {try(var scope=AlvosComprovanteVedacit.restringir(new AlvosComprovanteVedacit.Alvo(key("55","9"),ct))){assertThrows(IllegalStateException.class,this::run);}unsaved();}
    private static String key(String model,String last) {String base="35261012345678000123"+model+"00100000000110000000"+last;int sum=0;for(int i=42,w=2;i>=0;i--,w=w==9?2:w+1)sum+=(base.charAt(i)-'0')*w;int d=11-sum%11;return base+(d>=10?0:d);}
}
