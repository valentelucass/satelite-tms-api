package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocument;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocument.Tipo;

@ExtendWith(MockitoExtension.class)
class ComprovanteVedacitDirigidoTest {
    @Mock LogIntegracaoRepository repository;
    @Mock EtlRegistroService registro;
    @InjectMocks EtlRepescagemService service;
    private AlvosComprovanteVedacit.Alvo alvo;

    private static String chave(String modelo, String semente) {
        String base=semente.repeat(20)+modelo+semente.repeat(21);
        int soma=0;
        for(int i=42,peso=2;i>=0;i--,peso=peso==9?2:peso+1)soma+=(base.charAt(i)-'0')*peso;
        int dv=11-soma%11;
        return base+(dv>=10?0:dv);
    }
    @BeforeEach void preparar() { alvo=new AlvosComprovanteVedacit.Alvo(chave("55","1"),chave("57","2")); }
    private VedacitSftpDocument documento(String cte, Tipo tipo) {
        return new VedacitSftpDocument(tipo,"administrativo",cte,alvo.chaveNfe(),1,Instant.EPOCH,null);
    }
    @Test void semArquivoDoParNaoConsultaNemEnvia() throws Exception {
        var result=service.reprocessarCanhotosDirigidosSftpVedacit(List.of(alvo),List.of(documento(chave("57","3"),Tipo.COMPROVANTE)),0);
        assertEquals(1,result.pendentes());
        verifyNoInteractions(repository,registro);
    }
    @Test void xmlNaoSubstituiComprovante() throws Exception {
        var result=service.reprocessarCanhotosDirigidosSftpVedacit(List.of(alvo),List.of(documento(alvo.chaveCte(),Tipo.XML_CTE)),0);
        assertEquals(1,result.pendentes());
        verifyNoInteractions(repository,registro);
    }
    @Test void semAuditoriaExataMantemPendenteSemCriarEstado() throws Exception {
        when(repository.findTopBySistemaDestinoAndChaveNfeAndChaveCteAndArquivadoFalseOrderByDataProcessamentoDescIdDesc("VEDACIT",alvo.chaveNfe(),alvo.chaveCte())).thenReturn(Optional.empty());
        var result=service.reprocessarCanhotosDirigidosSftpVedacit(List.of(alvo),List.of(documento(alvo.chaveCte(),Tipo.COMPROVANTE)),0);
        assertEquals(1,result.pendentes());
        verifyNoInteractions(registro);
        verify(repository).findTopBySistemaDestinoAndChaveNfeAndChaveCteAndArquivadoFalseOrderByDataProcessamentoDescIdDesc("VEDACIT",alvo.chaveNfe(),alvo.chaveCte());
        verifyNoMoreInteractions(repository);
    }
    @Test void reutilizaServicoExistenteComParRestritoESemFilaAmpla() throws Exception {
        LogIntegracaoModel log=mock(LogIntegracaoModel.class);
        when(repository.findTopBySistemaDestinoAndChaveNfeAndChaveCteAndArquivadoFalseOrderByDataProcessamentoDescIdDesc("VEDACIT",alvo.chaveNfe(),alvo.chaveCte())).thenReturn(Optional.of(log));
        when(registro.reprocessarCanhotoVedacitPorCte(log)).thenAnswer(invocation->{
            assertDoesNotThrow(()->AlvosComprovanteVedacit.validarEnvio(alvo.chaveNfe(),alvo.chaveCte()));
            assertThrows(IllegalStateException.class,()->AlvosComprovanteVedacit.validarEnvio(alvo.chaveNfe(),chave("57","3")));
            return ResultadoRegistro.JA_PROCESSADO;
        });
        var result=service.reprocessarCanhotosDirigidosSftpVedacit(List.of(alvo),List.of(documento(alvo.chaveCte(),Tipo.COMPROVANTE)),0);
        assertEquals(1,result.ignorados());assertEquals(0,result.enviados());
        verify(registro).reprocessarCanhotoVedacitPorCte(log);
        verifyNoMoreInteractions(registro);
        assertDoesNotThrow(()->AlvosComprovanteVedacit.validarEnvio(alvo.chaveNfe(),chave("57","3")));
    }
    @Test void listaVaziaFalhaAntesDeConsultar() {
        assertThrows(IllegalArgumentException.class,()->service.reprocessarCanhotosDirigidosSftpVedacit(List.of(),List.of(),0));
        verifyNoInteractions(repository,registro);
    }
}
