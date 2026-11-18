package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.IntegracaoEventoRepository;
import com.example.satelite.repositories.LogIntegracaoRepository;

class EtlEstadoXmlConciliacaoTest {
    @Test void registraConciliacaoComoAvaliacaoSemCriarEnvioOuAceiteComprovante() {
        var repo=mock(LogIntegracaoRepository.class);var events=mock(IntegracaoEventoRepository.class);
        var state=new EtlEstadoIntegracaoService(repo);ReflectionTestUtils.setField(state,"eventosRepository",events);
        var date=LocalDateTime.of(2026,10,20,9,0);var r=LogIntegracaoModel.builder().id(42L).sistemaDestino("VEDACIT")
                .chaveNfe("nf-teste").chaveCte("ct-teste").statusDados("SUCESSO").dataProcessamentoDados(date)
                .statusCanhoto("PENDENTE_FOTO").tentativasDados(3).tentativasCanhoto(0).build();
        when(repo.save(r)).thenReturn(r);assertSame(r,state.salvarConciliacaoXmlSemEnvio(r,10L,77L));
        var captured=ArgumentCaptor.forClass(IntegracaoEventoRepository.Evento.class);verify(events).registrar(captured.capture());
        var e=captured.getValue();assertEquals("AVALIACAO",e.tipo());assertEquals("DADOS",e.etapa());assertEquals("CONFIRMADO",e.acao());
        assertEquals("XML_CONCILIADO_LOG_FONTE:10;ENTREGA_EVENTO:77",e.motivo());assertEquals(date,r.getDataProcessamentoDados());assertNull(r.getDataProcessamentoCanhoto());assertEquals(3,r.getTentativasDados());assertEquals(0,r.getTentativasCanhoto());
    }
    @Test void recusaPersistirSemProvasDatadas() {
        var repo=mock(LogIntegracaoRepository.class);var state=new EtlEstadoIntegracaoService(repo);
        ReflectionTestUtils.setField(state,"eventosRepository",mock(IntegracaoEventoRepository.class));
        var r=LogIntegracaoModel.builder().sistemaDestino("VEDACIT").statusDados("SUCESSO").build();
        assertThrows(IllegalArgumentException.class,()->state.salvarConciliacaoXmlSemEnvio(r,10L,77L));verify(repo,never()).save(any());
    }
}
