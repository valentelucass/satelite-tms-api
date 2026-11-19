package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlvosComprovanteVedacitTest {
    @TempDir Path pasta;
    private static String chave(String modelo, String semente) {
        String base = semente.repeat(20) + modelo + semente.repeat(21);
        int soma = 0;
        for (int i=42, peso=2; i>=0; i--, peso=peso==9?2:peso+1) soma += (base.charAt(i)-'0')*peso;
        int dv=11-soma%11;
        return base+(dv>=10?0:dv);
    }
    private static String nfe() { return chave("55", "1"); }
    private static String cte() { return chave("57", "2"); }
    private Path arquivo(String texto) throws Exception {
        Path path = pasta.resolve("alvos.txt");
        Files.writeString(path, texto);
        return path;
    }

    @Test void rejeitaCaminhoVazioOuAusente() {
        assertThrows(IllegalArgumentException.class, () -> AlvosComprovanteVedacit.carregar(" "));
        assertThrows(IllegalArgumentException.class, () -> AlvosComprovanteVedacit.carregar(pasta.resolve("ausente").toString()));
    }
    @Test void rejeitaArquivoVazioOuSomenteComentarios() throws Exception {
        Path path = arquivo("# lista\n\n");
        assertThrows(IllegalArgumentException.class, () -> AlvosComprovanteVedacit.carregar(path.toString()));
    }
    @Test void rejeitaLinhaInvalidaMesmoDepoisDeParValido() throws Exception {
        Path path = arquivo(nfe()+";"+cte()+"\ninválido\n");
        assertThrows(IllegalArgumentException.class, () -> AlvosComprovanteVedacit.carregar(path.toString()));
    }
    @Test void exigeParCompletoSemAmpliarPorNfe() throws Exception {
        Path path = arquivo(nfe());
        assertThrows(IllegalArgumentException.class, () -> AlvosComprovanteVedacit.carregar(path.toString()));
    }
    @Test void rejeitaModeloTrocadoEDigitoVerificadorInvalido() {
        assertThrows(IllegalArgumentException.class, () -> new AlvosComprovanteVedacit.Alvo(cte(), nfe()));
        String errado=nfe().substring(0,43)+((nfe().charAt(43)-'0'+1)%10);
        assertThrows(IllegalArgumentException.class, () -> new AlvosComprovanteVedacit.Alvo(errado, cte()));
    }
    @Test void preservaOrdemRemoveDuplicadosEAceitaBom() throws Exception {
        Path path = arquivo("\uFEFF# lista\n"+nfe()+";"+cte()+"\n"+nfe()+";"+cte()+"\n");
        var alvos = AlvosComprovanteVedacit.carregar(path.toString());
        assertEquals(1, alvos.size());
        assertEquals(new AlvosComprovanteVedacit.Alvo(nfe(), cte()), alvos.get(0));
    }
    @Test void rejeitaArquivoExcessivoAntesDeLerLinhas() throws Exception {
        Path path = arquivo(" ".repeat(33*1024));
        assertThrows(IllegalArgumentException.class, () -> AlvosComprovanteVedacit.carregar(path.toString()));
    }
    @Test void restringeAmbasAsChavesERestauraComportamentoAoFechar() {
        try (var escopo = AlvosComprovanteVedacit.restringir(new AlvosComprovanteVedacit.Alvo(nfe(), cte()))) {
            assertDoesNotThrow(() -> AlvosComprovanteVedacit.validarEnvio(nfe(), cte()));
            assertThrows(IllegalStateException.class, () -> AlvosComprovanteVedacit.validarEnvio(nfe(), "3".repeat(44)));
            assertThrows(IllegalStateException.class, () -> AlvosComprovanteVedacit.validarEnvio("3".repeat(44), cte()));
        }
        assertDoesNotThrow(() -> AlvosComprovanteVedacit.validarEnvio(nfe(), "3".repeat(44)));
    }
    @Test void escoposAninhadosRestauramParAnterior() {
        var primeiro = new AlvosComprovanteVedacit.Alvo(nfe(), cte());
        var segundo = new AlvosComprovanteVedacit.Alvo(nfe(), chave("57", "3"));
        try (var externo = AlvosComprovanteVedacit.restringir(primeiro)) {
            try (var interno = AlvosComprovanteVedacit.restringir(segundo)) {
                assertDoesNotThrow(() -> AlvosComprovanteVedacit.validarEnvio(segundo.chaveNfe(), segundo.chaveCte()));
            }
            assertDoesNotThrow(() -> AlvosComprovanteVedacit.validarEnvio(primeiro.chaveNfe(), primeiro.chaveCte()));
        }
    }
}
