package com.example.satelite.services.etl;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.*;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.utils.ContextoCicloIntegracao;

class EtlEventosIntegracaoTest {
    @Test void revisitaRetidaRegistraAvaliacaoSemMoverEstadoDataOuAgenda() {
        var logs=mock(LogIntegracaoRepository.class); var eventos=mock(IntegracaoEventoRepository.class);
        var service=new EtlEstadoIntegracaoService(logs);
        ReflectionTestUtils.setField(service,"eventosRepository",eventos);
        var ontem=LocalDateTime.of(2026,9,20,10,0);
        var registro=LogIntegracaoModel.builder().id(1L).sistemaDestino("VEDACIT").chaveCte("cte")
                .statusDados("ERRO_DESTINO").mensagemErroDados("ORIGEM_XML_HTTP_401")
                .dataProcessamentoDados(ontem).dadosProximaAvaliacaoEm(ontem.plusMinutes(30)).build();
        service.registrarReavaliacaoXmlSemEnvio(registro);
        assertEquals(ontem,registro.getDataProcessamentoDados());
        assertEquals(ontem.plusMinutes(30),registro.getDadosProximaAvaliacaoEm());
        verify(logs,never()).save(any());
        verify(eventos).registrar(argThat(e->e.tipo().equals("AVALIACAO") && e.acao().equals("AGUARDANDO_ORIGEM")));
    }
    @Test void avaliacoesMantemIdentidadeIdempotenteIdadeECicloMasNuncaContamComoTransmissao() {
        var logs=mock(LogIntegracaoRepository.class); var eventos=mock(IntegracaoEventoRepository.class);
        var relogio=mock(AuditoriaDataHoraService.class);
        var service=new EtlEstadoIntegracaoService(logs,relogio);
        ReflectionTestUtils.setField(service,"eventosRepository",eventos);
        when(logs.save(any())).thenAnswer(i->i.getArgument(0));
        var inicio=LocalDateTime.of(2026,9,21,10,0);
        when(relogio.agora()).thenReturn(inicio,inicio.plusHours(1),inicio.plusHours(2));
        var registro=LogIntegracaoModel.builder().id(1L).sistemaDestino("VEDACIT").chaveCte("cte")
                .statusDados("RECEBIDO").statusCanhoto("SUCESSO").build();
        var ciclo=UUID.randomUUID();
        try(var escopo=ContextoCicloIntegracao.abrir(ciclo)) {
            service.aplicarResultadoXml(registro,ResultadoIntegracao.erroDados("ORIGEM_XML_HTTP_401"));
            service.salvar(registro); service.salvar(registro);
            assertEquals(inicio,registro.getDadosPendenteDesde());
            assertEquals(inicio.plusMinutes(30),registro.getDadosProximaAvaliacaoEm());
            service.aplicarResultadoXml(registro,ResultadoIntegracao.erroDados("ORIGEM_XML_AUTENTICACAO_EM_ESPERA"));
            service.salvar(registro);
            assertEquals(inicio,registro.getDadosPendenteDesde());
            service.aplicarResultadoXml(registro,ResultadoIntegracao.vedacitConcluido("SUCESSO","SUCESSO"));
            service.salvar(registro);
            assertNull(registro.getDadosPendenteDesde()); assertNull(registro.getDadosProximaAvaliacaoEm());
        }
        var captor=ArgumentCaptor.forClass(IntegracaoEventoRepository.Evento.class);
        verify(eventos,times(4)).registrar(captor.capture());
        var lista=captor.getAllValues();
        assertEquals(lista.get(0).id(),lista.get(1).id()); assertNotEquals(lista.get(1).id(),lista.get(2).id());
        assertEquals("AGUARDANDO_ORIGEM",lista.get(0).acao()); assertEquals("CONFIRMADO",lista.get(3).acao());
        assertTrue(lista.stream().allMatch(e->e.tipo().equals("AVALIACAO") && e.etapa().equals("DADOS") && ciclo.equals(e.ciclo())));
        assertNull(ContextoCicloIntegracao.atual());
    }

    @Test void timeoutNuncaRecebeProximaTentativaAutomaticaEConfirmacaoMantemPrimeiraData() {
        var logs=mock(LogIntegracaoRepository.class); var eventos=mock(IntegracaoEventoRepository.class);
        var service=new EtlEstadoIntegracaoService(logs);
        ReflectionTestUtils.setField(service,"eventosRepository",eventos);
        when(logs.save(any())).thenAnswer(i->i.getArgument(0));
        var registro=LogIntegracaoModel.builder().id(1L).sistemaDestino("VEDACIT")
                .statusDados("SUCESSO").statusCanhoto("ERRO_DESTINO").canhotoClassificacaoOperacional("TIMEOUT_AMBIGUO").build();
        service.aplicarResultadoIntegracao(registro,ResultadoIntegracao.erroCanhoto("SUCESSO","Read timed out"));
        service.salvar(registro);
        assertEquals("AGUARDANDO_CONFIRMACAO",registro.getCanhotoAcaoPainel()); assertNull(registro.getCanhotoProximaAvaliacaoEm());
        registro.setStatusCanhoto("SUCESSO");
        var aceita=LocalDateTime.of(2026,9,10,10,0); registro.setDataProcessamentoCanhoto(aceita);
        clearInvocations(eventos);
        service.aplicarResultadoIntegracao(registro,ResultadoIntegracao.erroCanhoto("SUCESSO","Read timed out"));
        service.salvar(registro);
        assertEquals("SUCESSO",registro.getStatusCanhoto()); assertEquals(aceita,registro.getDataProcessamentoCanhoto());
        verifyNoInteractions(eventos);
    }
}
