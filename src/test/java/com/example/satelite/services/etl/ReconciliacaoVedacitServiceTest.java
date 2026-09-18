package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.satelite.dto.auditoria.ReconciliacaoVedacitDTO.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.satelite.repositories.ReconciliacaoVedacitRepository;
import com.example.satelite.services.origem.sftp.vedacit.*;
import com.example.satelite.services.vedacit.VedacitConsultaReconciliacaoService;

class ReconciliacaoVedacitServiceTest {
    static final LocalDateTime AGORA=LocalDateTime.of(2026,9,13,2,0);
    final ReconciliacaoVedacitRepository repo=mock(ReconciliacaoVedacitRepository.class);
    final VedacitSftpClientFactory factory=mock(VedacitSftpClientFactory.class);
    final VedacitSftpClient sftp=mock(VedacitSftpClient.class);
    final VedacitConsultaReconciliacaoService consulta=mock(VedacitConsultaReconciliacaoService.class);
    final ReconciliacaoVedacitAcoesService acoes=mock(ReconciliacaoVedacitAcoesService.class);
    final ReconciliacaoVedacitService service=new ReconciliacaoVedacitService(repo,factory,consulta,acoes);
    final Execucao execucao=new Execucao(1,LocalDate.of(2026,9,13),0,9999,"EM_EXECUCAO",null,null,null,null,null,null);
    @BeforeEach void preparar() {
        ReflectionTestUtils.setField(service,"pausaMs",0L);
        when(factory.criarClientesHabilitados()).thenReturn(List.of(new VedacitSftpClientFactory.ClienteSftp("VEDACIT",sftp,100)));
        when(sftp.listarInventarioComprovantes()).thenReturn(new VedacitSftpInventory(List.of(),List.of()));
        when(consulta.consultarXml(anyString())).thenReturn(new ResultadoConsulta("CONSULTA_SEM_PERMISSAO",true));
        when(consulta.consultarComprovante(anyString())).thenReturn(new ResultadoConsulta("CONSULTA_SEM_PERMISSAO",true));
    }
    static Candidato candidato(long id,String xml,String pod,String classe,boolean arquivado) {
        boolean confirmado="SUCESSO".equals(xml);
        return new Candidato(id,"3".repeat(44),"4".repeat(44),"4".repeat(44),"VEDACIT",arquivado,xml,pod,classe,
                confirmado?AGORA.minusDays(1):null,null,0,confirmado?AGORA.minusDays(1):null,confirmado,false,null,false,"comprovantes/a.jpg");
    }
    @Test void timeoutEhConsultadoMasNuncaReenviado() {
        var r=service.revisar(candidato(1,"SUCESSO","ERRO_DESTINO","TIMEOUT_AMBIGUO",false),service.new Fontes(execucao),AGORA);
        assertEquals("CONSULTA_SEM_PERMISSAO",r.comprovante());
        assertEquals("ENVIO_RETIDO_AGUARDA_CONFIRMACAO_EXATA",r.acao());
        verifyNoInteractions(acoes);
    }
    @Test void erroArquivadoEhRevisadoSemReativacaoOuEnvio() {
        var r=service.revisar(candidato(1,"ERRO_DESTINO","ERRO_DESTINO","BLOQUEADO_ORIGEM",true),service.new Fontes(execucao),AGORA);
        assertEquals("ARQUIVADO_CONFERIDO_SEM_REATIVACAO",r.acao());
        verify(consulta).consultarXml(anyString());verifyNoInteractions(acoes);
    }
    @Test void origemNegadaInterrompeChamadasMasAuditaTodosOsItens() {
        var a=candidato(1,"PENDENTE_ORIGEM","PENDENTE_FOTO","BLOQUEADO_ORIGEM",false);
        var b=candidato(2,"PENDENTE_ORIGEM","PENDENTE_FOTO","BLOQUEADO_ORIGEM",false);
        when(repo.pagina(execucao,100)).thenReturn(List.of(a,b));
        when(acoes.recuperarXml(a)).thenReturn(new ReconciliacaoVedacitAcoesService.ResultadoAcao("ERRO","ORIGEM_XML_HTTP_401"));
        when(sftp.buscarXmlCte(anyString(),anyString())).thenReturn(java.util.Optional.empty());
        assertFalse(service.processarPagina(execucao,()->AGORA,AGORA.plusHours(4),AGORA.plusDays(1)));
        verify(acoes,times(1)).recuperarXml(any());
        verify(repo).bloquearFonte(1,"ORIGEM_XML","XML_ORIGEM_SEM_PERMISSAO");
        var resultados=ArgumentCaptor.forClass(Revisao.class);
        verify(repo,times(2)).registrar(eq(execucao),any(),resultados.capture(),eq(AGORA),eq(AGORA.plusDays(1)));
        assertTrue(resultados.getAllValues().stream().allMatch(r->r.xml().equals("XML_ORIGEM_SEM_PERMISSAO")));
    }
    @Test void bloqueioPersistidoSobreviveAOutroProcessoSemNovaConsulta() {
        var e=new Execucao(1,execucao.dia(),0,99,"EM_EXECUCAO",null,"CONSULTA_SEM_PERMISSAO","CONSULTA_SEM_PERMISSAO",null,null,null);
        service.revisar(candidato(1,"ERRO_DESTINO","ERRO_DESTINO","TIMEOUT_AMBIGUO",true),service.new Fontes(e),AGORA);
        verifyNoInteractions(consulta,acoes);
    }
    @Test void fimDaJanelaPreservaItemAindaNaoRevisado() {
        when(repo.pagina(execucao,100)).thenReturn(List.of(candidato(1,"SUCESSO","ERRO_DESTINO","TIMEOUT_AMBIGUO",false)));
        assertFalse(service.processarPagina(execucao,()->AGORA.plusHours(4),AGORA.plusHours(4),AGORA.plusDays(1)));
        verify(repo,never()).registrar(any(),any(),any(),any(),any());verifyNoInteractions(consulta);
        verify(repo).pausar(1,AGORA.plusHours(4));
    }
    @Test void paginaVaziaFinalizaSomenteAVarredura() {
        when(repo.pagina(execucao,100)).thenReturn(List.of());
        assertTrue(service.processarPagina(execucao,()->AGORA,AGORA.plusHours(4),AGORA.plusDays(1)));
        verify(repo).concluir(1,AGORA);verifyNoInteractions(acoes,consulta,factory);
    }
    @Test void falhaIsoladaNaoImpedeConferenciaDoProximoDocumento() {
        var a=candidato(1,"SUCESSO","ERRO_DESTINO","TIMEOUT_AMBIGUO",false);
        var b=candidato(2,"SUCESSO","ERRO_DESTINO","TIMEOUT_AMBIGUO",false);
        when(repo.pagina(execucao,100)).thenReturn(List.of(a,b));
        when(consulta.consultarComprovante(anyString())).thenThrow(new IllegalStateException("segredo externo"))
                .thenReturn(new ResultadoConsulta("NAO_LOCALIZADO_NO_DESTINO",false));
        service.processarPagina(execucao,()->AGORA,AGORA.plusHours(4),AGORA.plusDays(1));
        var resultados=ArgumentCaptor.forClass(Revisao.class);
        verify(repo,times(2)).registrar(eq(execucao),any(),resultados.capture(),any(),any());
        assertEquals("FALHA_NA_CONFERENCIA",resultados.getAllValues().get(0).xml());
        assertFalse(resultados.getAllValues().get(0).detalhe().contains("segredo"));
        assertEquals("NAO_LOCALIZADO_NO_DESTINO",resultados.getAllValues().get(1).comprovante());
    }
    @Test void falhaDeCheckpointInterrompeAntesDePularOItem() {
        var c=candidato(1,"SUCESSO","ERRO_DESTINO","TIMEOUT_AMBIGUO",false);
        when(repo.pagina(execucao,100)).thenReturn(List.of(c,candidato(2,"SUCESSO","ERRO_DESTINO","TIMEOUT_AMBIGUO",false)));
        doThrow(new IllegalStateException("SQL_OFFLINE")).when(repo).registrar(any(),any(),any(),any(),any());
        assertThrows(IllegalStateException.class,()->service.processarPagina(execucao,()->AGORA,AGORA.plusHours(4),AGORA.plusDays(1)));
        verify(repo,times(1)).registrar(any(),any(),any(),any(),any());
        verify(repo,never()).concluir(anyLong(),any());
    }
    @Test void sucessoSemDataNaoViraNovoEnvioXml() {
        var c=new Candidato(1,"3".repeat(44),"4".repeat(44),"4".repeat(44),"VEDACIT",false,"SUCESSO","SUCESSO","SUCESSO",
                null,null,0,null,true,true,null,false,"a.jpg");
        var r=service.revisar(c,service.new Fontes(execucao),AGORA);
        assertEquals("CONSULTA_SEM_PERMISSAO",r.xml());
        assertEquals("COMPROVANTE_ACEITO_DATA_HISTORICA_INCERTA",r.comprovante());
        verify(acoes,never()).recuperarXml(any());verify(acoes,never()).recuperarComprovante(any(),any());
    }
    @Test void incompletoNaoEscolheDocumentoPelaMesmaNfe() {
        var c=new Candidato(1,null,null,null,"VEDACIT",false,"NAO_APLICAVEL","ERRO_DESTINO","BLOQUEADO_ORIGEM",
                null,null,0,null,false,false,null,false,"a.jpg");
        var r=service.revisar(c,service.new Fontes(execucao),AGORA);
        assertEquals("IDENTIDADE_FISCAL_INCOMPLETA",r.xml());verifyNoInteractions(acoes,consulta);
    }
    @Test void recuperacaoDesabilitadaMantemSomenteConferencia() {
        ReflectionTestUtils.setField(service,"recuperar",false);
        var r=service.revisar(candidato(1,"PENDENTE_ORIGEM","PENDENTE_FOTO","BLOQUEADO_ORIGEM",false),service.new Fontes(execucao),AGORA);
        assertEquals("XML_ELEGIVEL_RECUPERACAO_DESABILITADA",r.xml());verifyNoInteractions(acoes);
    }
    @Test void recusaDeNegocioNaoSuspendeEnviosDeOutrosDocumentos() {
        assertFalse(ReconciliacaoVedacitService.infraestruturaIndisponivel("Vedacit recusou o XML: cadastro incorreto"));
        assertTrue(ReconciliacaoVedacitService.infraestruturaIndisponivel("Read timed out"));
    }
}
