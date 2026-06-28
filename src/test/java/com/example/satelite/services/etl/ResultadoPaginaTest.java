package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ResultadoPaginaTest {

    @Test
    void deveIncrementarApenasFalhasInfraestruturaConsecutivas() {
        ResultadoPagina resultado = ResultadoPagina.vazio()
                .com(ResultadoRegistro.ERRO_INFRAESTRUTURA)
                .com(ResultadoRegistro.ERRO_INFRAESTRUTURA);

        assertEquals(2, resultado.falhasInfraestruturaConsecutivas());
        assertEquals(2, resultado.erros());
    }

    @Test
    void deveZerarFalhasInfraestruturaConsecutivasQuandoErroForDeNegocio() {
        ResultadoPagina resultado = ResultadoPagina.vazio()
                .com(ResultadoRegistro.ERRO_INFRAESTRUTURA)
                .com(ResultadoRegistro.ERRO);

        assertEquals(0, resultado.falhasInfraestruturaConsecutivas());
        assertEquals(2, resultado.erros());
    }

    @Test
    void deveZerarFalhasInfraestruturaConsecutivasQuandoRegistroNaoForErro() {
        ResultadoPagina resultado = ResultadoPagina.vazio()
                .com(ResultadoRegistro.ERRO_INFRAESTRUTURA)
                .com(ResultadoRegistro.PENDENTE_FOTO);

        assertEquals(0, resultado.falhasInfraestruturaConsecutivas());
        assertEquals(1, resultado.erros());
    }
}
