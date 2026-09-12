package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.example.satelite.repositories.ReconciliacaoVedacitRepository;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocumentSource;

class ReconciliacaoVedacitAcoesServiceTest {
    final SftpDocumentoLockService locks=mock(SftpDocumentoLockService.class);
    final EtlEstadoIntegracaoService estado=mock(EtlEstadoIntegracaoService.class);
    final EtlRegistroService registros=mock(EtlRegistroService.class);
    final ReconciliacaoVedacitRepository repo=mock(ReconciliacaoVedacitRepository.class);
    final ReconciliacaoVedacitAcoesService acoes=new ReconciliacaoVedacitAcoesService(locks,estado,registros,repo);
    @BeforeEach void preparar() {
        when(locks.executarComLock(anyString(),anyString(),anyString(),any()))
                .thenAnswer(i->Optional.ofNullable(((Supplier<?>)i.getArgument(3)).get()));
    }
    @Test void releituraQueEncontraSucessoConcorrenteImpedeEnvio() {
        var c=ReconciliacaoVedacitServiceTest.candidato(1,"PENDENTE_ORIGEM","PENDENTE_FOTO","BLOQUEADO_ORIGEM",false);
        var atual=LogIntegracaoModel.builder().id(1L).chaveNfe(c.nfe()).chaveCte(c.cte()).build();
        when(estado.buscarAtivoPorId(1L)).thenReturn(Optional.of(atual));
        when(estado.xmlVedacitConfirmado(c.cte())).thenReturn(true);
        assertEquals("HISTORICO_PROTEGIDO",acoes.recuperarXml(c).codigo());verifyNoInteractions(registros);
    }
    @Test void historicoIncertoDeOutroLogBloqueiaXmlMesmoComArquivoDisponivel() {
        var c=ReconciliacaoVedacitServiceTest.candidato(1,"PENDENTE_ORIGEM","PENDENTE_FOTO","BLOQUEADO_ORIGEM",false);
        when(estado.buscarAtivoPorId(1L)).thenReturn(Optional.of(LogIntegracaoModel.builder().chaveNfe(c.nfe()).chaveCte(c.cte()).build()));
        when(repo.xmlRetidoPorOutroHistorico(c.cte(),1)).thenReturn(true);
        assertEquals("HISTORICO_PROTEGIDO",acoes.recuperarXml(c).codigo());verifyNoInteractions(registros);
    }
    @Test void timeoutAparecidoDepoisDaSelecaoNuncaEhReenviado() {
        var c=ReconciliacaoVedacitServiceTest.candidato(1,"SUCESSO","PENDENTE_FOTO","PENDENTE_ENVIO",false);
        when(estado.buscarAtivoPorId(1L)).thenReturn(Optional.of(LogIntegracaoModel.builder()
                .chaveNfe(c.nfe()).chaveCte(c.cte()).canhotoClassificacaoOperacional("TIMEOUT_AMBIGUO").build()));
        assertEquals("HISTORICO_PROTEGIDO",acoes.recuperarComprovante(c,mock(VedacitSftpDocumentSource.class)).codigo());
        verifyNoInteractions(registros);
    }
    @Test void documentoArquivadoNaoAdquireLockNemChamaIntegracao() {
        var c=ReconciliacaoVedacitServiceTest.candidato(1,"PENDENTE_ORIGEM","PENDENTE_FOTO","BLOQUEADO_ORIGEM",true);
        assertEquals("RETIDO",acoes.recuperarXml(c).codigo());verifyNoInteractions(locks,registros,estado);
    }
}
