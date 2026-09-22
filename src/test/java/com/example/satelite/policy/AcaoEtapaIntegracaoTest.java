package com.example.satelite.policy;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AcaoEtapaIntegracaoTest {
    @ParameterizedTest
    @CsvSource({
        "ERRO_DESTINO,,ORIGEM_XML_HTTP_401,AGUARDANDO_ORIGEM,true",
        "ERRO_DESTINO,,ORIGEM_XML_AUTENTICACAO_EM_ESPERA,AGUARDANDO_ORIGEM,true",
        "ERRO_DESTINO,,SOAP_ANTERIOR_EM_ANDAMENTO: Read timed out,NOVA_TENTATIVA,true",
        "ERRO_DESTINO,,Read timed out,AGUARDANDO_CONFIRMACAO,false",
        "EM_PROCESSAMENTO,TIMEOUT_AMBIGUO,,AGUARDANDO_CONFIRMACAO,false",
        "ERRO_DESTINO,,ENVIO_XML_RESULTADO_DESCONHECIDO_RECONCILIACAO,AGUARDANDO_CONFIRMACAO,false",
        "ERRO_DESTINO,BLOQUEADO_DESTINO,,INTERVENCAO,false",
        "SUCESSO,TIMEOUT_AMBIGUO,Read timed out,CONFIRMADO,false",
        "PENDENTE_FOTO,,DATA_ENTREGA_AUSENTE,AGUARDANDO_ORIGEM,true",
        "RECEBIDO,,,AGUARDANDO_PROCESSAMENTO,true"
    })
    void proximaAcaoPreservaAceitesEExigeConferenciaParaResultadoDesconhecido(String status,String bloqueio,String mensagem,String acao,boolean automatica) {
        var resultado=AcaoEtapaIntegracao.classificar(status,bloqueio,mensagem);
        assertEquals(acao,resultado.name()); assertEquals(automatica,resultado.permiteReavaliacaoAutomatica());
    }
}

