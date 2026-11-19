package com.example.satelite.utils;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class FalhaIntegracaoSanitizadaTest {
    @Test void naoExibeSqlPayloadCaminhoOuSegredoDaExcecao() {
        var e = new IllegalStateException("SQL com payload e credencial=segredo", new SQLException("conteudo privado", "state", 1205));
        assertEquals("SQL_1205", FalhaIntegracaoSanitizada.resumir(e));
        assertEquals("IllegalStateException", FalhaIntegracaoSanitizada.resumir(new IllegalStateException("token=segredo")));
    }
}
