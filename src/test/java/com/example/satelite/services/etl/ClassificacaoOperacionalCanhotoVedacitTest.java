package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.example.satelite.services.ResultadoIntegracao;

class ClassificacaoOperacionalCanhotoVedacitTest {

    @Test
    void deveSepararTimeoutRecusaOrigemETecnico() {
        assertEquals(ClassificacaoOperacionalCanhotoVedacit.TIMEOUT_AMBIGUO,
                ClassificacaoOperacionalCanhotoVedacit.paraErro("java.net.SocketTimeoutException: Read timed out"));
        assertEquals(ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_DESTINO,
                ClassificacaoOperacionalCanhotoVedacit.paraErro("Vedacit recusou o canhoto: digitalização inválida"));
        assertEquals(ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_ORIGEM,
                ClassificacaoOperacionalCanhotoVedacit.paraErro("Formato de imagem nao suportado para compressao Vedacit"));
        assertEquals(ClassificacaoOperacionalCanhotoVedacit.PENDENTE_TECNICO,
                ClassificacaoOperacionalCanhotoVedacit.paraErro("HTTP 503 temporário"));
    }

    @Test
    void deveClassificarResultadoDeErroDoSoapComoTimeoutAmbiguo() {
        ResultadoIntegracao resultado = ResultadoIntegracao.erroCanhoto(
                ResultadoIntegracao.STATUS_SUCESSO,
                "java.net.SocketTimeoutException: Read timed out"
        );

        assertEquals(ClassificacaoOperacionalCanhotoVedacit.TIMEOUT_AMBIGUO,
                EtlRegistroService.classificarResultadoCanhotoVedacit(resultado));
    }
}
