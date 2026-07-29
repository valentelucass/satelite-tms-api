package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFileRetentionServiceTest {

    private static final Instant AGORA = Instant.parse("2026-07-29T20:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void deveRemoverLogsAntigosEManterSomenteAQuantidadeConfigurada() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path logs = Files.createDirectories(projectRoot.resolve("logs"));
        Path pastaAntiga = Files.createDirectories(logs.resolve("2026-06-01"));
        Path antigo = criarLog(pastaAntiga, "antigo.log", AGORA.minusSeconds(31L * 24 * 60 * 60));
        Path maisAntigoRecente = criarLog(logs, "recente-1.log", AGORA.minusSeconds(3 * 60 * 60));
        Path recente = criarLog(logs, "recente-2.out", AGORA.minusSeconds(2 * 60 * 60));
        Path maisRecente = criarLog(logs, "recente-3.err", AGORA.minusSeconds(60 * 60));
        Path ignorado = criarLog(logs, "evidencia.txt", AGORA.minusSeconds(40L * 24 * 60 * 60));

        LogFileRetentionService service = new LogFileRetentionService(
                projectRoot,
                Path.of("logs"),
                2,
                30,
                500,
                Clock.fixed(AGORA, ZoneOffset.UTC)
        );

        LogFileRetentionService.LogRetentionResult result = service.executarRetencao();

        assertEquals(4, result.totalEncontrado());
        assertEquals(2, result.removidos());
        assertFalse(Files.exists(antigo));
        assertFalse(Files.exists(pastaAntiga));
        assertFalse(Files.exists(maisAntigoRecente));
        assertTrue(Files.exists(recente));
        assertTrue(Files.exists(maisRecente));
        assertTrue(Files.exists(ignorado));
    }

    @Test
    void naoDevePermitirLimpezaForaDaPastaDoProjeto() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path logsExternos = Files.createDirectories(temporaryDirectory.resolve("logs-externos"));
        Path logExterno = criarLog(logsExternos, "externo.log", AGORA.minusSeconds(40L * 24 * 60 * 60));

        LogFileRetentionService service = new LogFileRetentionService(
                projectRoot,
                logsExternos,
                1,
                1,
                1,
                Clock.fixed(AGORA, ZoneOffset.UTC)
        );

        LogFileRetentionService.LogRetentionResult result = service.executarRetencao();

        assertTrue(result.ignorado());
        assertTrue(Files.exists(logExterno));
    }

    private Path criarLog(Path directory, String nome, Instant ultimaModificacao) throws Exception {
        Path file = Files.writeString(directory.resolve(nome), "log");
        Files.setLastModifiedTime(file, FileTime.from(ultimaModificacao));
        return file;
    }
}
