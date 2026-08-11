package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecuperacaoDirigidaVedacitRunnerTest {

    @TempDir
    Path diretorioTemporario;

    @Test
    void deveLerSomenteChavesNfeValidasSemDuplicidadeERespeitandoLimite() throws Exception {
        String chaveA = "35260860642774001209550010002344161634484264";
        String chaveB = "35260860642774001209550010002344171634484265";
        Path arquivo = diretorioTemporario.resolve("nfes.txt");
        Files.writeString(arquivo, String.join(System.lineSeparator(), chaveA, "invalida", chaveA, chaveB));

        assertEquals(List.of(chaveA), RecuperacaoDirigidaVedacitRunner.carregarChaves(arquivo, 1));
        assertEquals(List.of(chaveA, chaveB), RecuperacaoDirigidaVedacitRunner.carregarChaves(arquivo, 50));
    }
}
