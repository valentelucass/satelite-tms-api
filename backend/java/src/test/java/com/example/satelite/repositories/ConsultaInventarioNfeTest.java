package com.example.satelite.repositories;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsultaInventarioNfeTest {
    private static final String CHAVE = "0".repeat(43) + "1";

    @Test
    void preservaZerosERepeticoesNosLotesValidos() {
        for (int tamanho : List.of(32, 128, 499, 500)) {
            var chaves = Collections.nCopies(tamanho, CHAVE);
            String esperado = "[" + String.join(",", Collections.nCopies(tamanho, "\"" + CHAVE + "\"")) + "]";
            assertEquals(esperado, ConsultaInventarioNfeImpl.loteJson(chaves).orElseThrow());
        }
    }

    @Test
    void comparaOOriginalQuandoUmaChaveNaoPodeSerConvertida() {
        var lote = new ArrayList<>(Collections.nCopies(32, CHAVE));
        for (String invalida : new String[]{null, "", CHAVE + "0", "9".repeat(43), "０".repeat(44),
                "١".repeat(44), "A" + CHAVE.substring(1), CHAVE + " ", "\"" + CHAVE.substring(1)}) {
            lote.set(19, invalida);
            assertTrue(ConsultaInventarioNfeImpl.loteJson(lote).isEmpty());
        }
        assertTrue(ConsultaInventarioNfeImpl.loteJson(null).isEmpty());
        for (int tamanho : List.of(0, 1, 31, 501)) {
            assertTrue(ConsultaInventarioNfeImpl.loteJson(Collections.nCopies(tamanho,CHAVE)).isEmpty());
        }
    }
}
