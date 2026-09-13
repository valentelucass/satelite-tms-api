package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ResultadoXmlDiagnosticoTest {
    @Test void agregaPaginasERecuperacaoSemPerderBloqueioOuTransformarEmSucesso() {
        var pagina = ResultadoPagina.vazio().com(ResultadoRegistro.RETIDO_ACESSO_ORIGEM)
                .com(ResultadoRegistro.JA_PROCESSADO).comCircuitoAberto().comFimJanelaRetroativa()
                .comInterrupcaoDeCiclo();
        assertEquals(1, pagina.retidos());
        assertEquals(1, pagina.retidosAcessoOrigem());
        var origem = ResultadoDestino.vazio("VEDACIT").comPagina(pagina).encerrar("fim");
        var recuperacao = ResultadoDestino.vazio("VEDACIT").comRegistros(
                ResultadoPagina.vazio().com(ResultadoRegistro.RETIDO_ACESSO_ORIGEM));
        var total = origem.combinar(recuperacao, "fim");
        assertEquals(3, total.recebidos());
        assertEquals(2, total.erros());
        assertEquals(0, total.enviados());
        assertEquals(2, total.retidosAcessoOrigem());
        assertTrue(total.motivoFalhaXml().startsWith("XML_ACESSO_ORIGEM:"));
    }

    @Test void falhasMistasECriticasNaoSaoApresentadasComoSomenteAcesso() {
        var bloqueio = ResultadoDestino.vazio("VEDACIT").comRegistros(
                ResultadoPagina.vazio().com(ResultadoRegistro.RETIDO_ACESSO_ORIGEM));
        assertTrue(bloqueio.comRegistros(ResultadoPagina.vazio().com(ResultadoRegistro.ERRO))
                .motivoFalhaXml().startsWith("XML_FALHAS_MISTAS:"));
        assertTrue(bloqueio.comErroCritico("falha de auditoria").motivoFalhaXml().startsWith("XML_PROCESSAMENTO:"));
        assertTrue(bloqueio.comErroCritico(new IllegalStateException("falha de pagina"))
                .motivoFalhaXml().startsWith("XML_PROCESSAMENTO:"));
    }
}
