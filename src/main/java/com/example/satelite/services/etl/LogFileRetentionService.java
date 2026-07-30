package com.example.satelite.services.etl;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Mantém apenas arquivos técnicos descartáveis dentro de {@code logs/}; nunca toca a auditoria SQL.
 */
@Service
@ConditionalOnExpression("'${APP_LOG_RETENTION_ENABLED:true}'.equalsIgnoreCase('true')")
public class LogFileRetentionService {

    private static final Logger log = LoggerFactory.getLogger(LogFileRetentionService.class);

    private static final Set<String> EXTENSOES_PERMITIDAS = Set.of(".log", ".out", ".err");
    private static final Comparator<Path> MAIS_ANTIGO_PRIMEIRO = Comparator
            .comparing(LogFileRetentionService::ultimaModificacaoSemFalhar)
            .thenComparing(path -> path.toString());

    private final Path projectRoot;
    private final Path logsDirectory;
    private final int maxFiles;
    private final int maxAgeDays;
    private final long maxTotalBytes;
    private final Clock clock;

    public LogFileRetentionService(
            @Value("${APP_LOG_RETENTION_DIRECTORY:logs}") String logsDirectory,
            @Value("${LOG_RETENTION_MAX_FILES:20}") int maxFiles,
            @Value("${LOG_RETENTION_MAX_AGE_DAYS:30}") int maxAgeDays,
            @Value("${LOG_RETENTION_MAX_TOTAL_MB:500}") long maxTotalMb) {
        this(
                Path.of(System.getProperty("user.dir")),
                Path.of(logsDirectory),
                maxFiles,
                maxAgeDays,
                maxTotalMb,
                Clock.systemDefaultZone()
        );
    }

    LogFileRetentionService(
            Path projectRoot,
            Path logsDirectory,
            int maxFiles,
            int maxAgeDays,
            long maxTotalMb,
            Clock clock) {
        if (maxFiles < 1 || maxAgeDays < 1 || maxTotalMb < 1) {
            throw new IllegalArgumentException("Os limites de retencao de logs devem ser maiores que zero.");
        }

        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.logsDirectory = (logsDirectory.isAbsolute() ? logsDirectory : this.projectRoot.resolve(logsDirectory))
                .toAbsolutePath()
                .normalize();
        this.maxFiles = maxFiles;
        this.maxAgeDays = maxAgeDays;
        this.maxTotalBytes = Math.multiplyExact(maxTotalMb, 1024L * 1024L);
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${APP_LOG_RETENTION_INITIAL_DELAY_MS:60000}",
            fixedDelayString = "${APP_LOG_RETENTION_INTERVAL_MS:900000}"
    )
    public void executarRetencaoAgendada() {
        executarRetencao();
    }

    public LogRetentionResult executarRetencao() {
        if (!logsDirectory.startsWith(projectRoot)) {
            log.error("Retencao de logs ignorada: diretorio fora do projeto configurado: {}", logsDirectory);
            return LogRetentionResult.resultadoIgnorado();
        }
        if (!Files.isDirectory(logsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return LogRetentionResult.resultadoVazio();
        }

        List<Path> files = listarArquivosElegiveis();
        Set<Path> selectedForRemoval = selecionarArquivosParaRemocao(files);
        int removed = removerArquivos(selectedForRemoval);
        int emptyDirectoriesRemoved = removerDiretoriosVazios();
        long remainingBytes = files.stream()
                .filter(file -> !selectedForRemoval.contains(file))
                .mapToLong(this::tamanhoSemFalhar)
                .sum();

        if (removed > 0 || emptyDirectoriesRemoved > 0) {
            log.info("Retencao de logs concluida: arquivosRemovidos={}, diretoriosVaziosRemovidos={}, restantes={}, tamanhoRestanteMb={}",
                    removed,
                    emptyDirectoriesRemoved,
                    files.size() - removed,
                    Math.round((remainingBytes / 1024.0 / 1024.0) * 100.0) / 100.0);
        }
        return new LogRetentionResult(files.size(), removed, remainingBytes, false);
    }

    private List<Path> listarArquivosElegiveis() {
        try (Stream<Path> stream = Files.walk(logsDirectory)) {
            return stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(this::temExtensaoPermitida)
                    .sorted(MAIS_ANTIGO_PRIMEIRO)
                    .toList();
        } catch (IOException exception) {
            log.warn("Falha ao listar logs para retencao em {}: {}", logsDirectory, exception.getMessage());
            return List.of();
        }
    }

    private Set<Path> selecionarArquivosParaRemocao(List<Path> files) {
        Instant cutoff = Instant.now(clock).minus(maxAgeDays, ChronoUnit.DAYS);
        Set<Path> selected = new HashSet<>();
        List<Path> retained = new ArrayList<>();

        for (Path file : files) {
            if (ultimaModificacaoSemFalhar(file).toInstant().isBefore(cutoff)) {
                selected.add(file);
            } else {
                retained.add(file);
            }
        }

        retained.sort(MAIS_ANTIGO_PRIMEIRO.reversed());
        for (int index = maxFiles; index < retained.size(); index++) {
            selected.add(retained.get(index));
        }

        retained = retained.stream()
                .filter(file -> !selected.contains(file))
                .sorted(MAIS_ANTIGO_PRIMEIRO)
                .toList();
        long retainedBytes = retained.stream().mapToLong(this::tamanhoSemFalhar).sum();
        for (Path file : retained) {
            if (retainedBytes <= maxTotalBytes) {
                break;
            }
            selected.add(file);
            retainedBytes -= tamanhoSemFalhar(file);
        }
        return selected;
    }

    private int removerArquivos(Set<Path> selectedForRemoval) {
        int removed = 0;
        for (Path file : selectedForRemoval.stream().sorted(MAIS_ANTIGO_PRIMEIRO).toList()) {
            try {
                if (Files.deleteIfExists(file)) {
                    removed++;
                }
            } catch (IOException exception) {
                log.warn("Nao foi possivel remover log antigo {}: {}", file, exception.getMessage());
            }
        }
        return removed;
    }

    private int removerDiretoriosVazios() {
        try (Stream<Path> stream = Files.walk(logsDirectory)) {
            List<Path> directories = stream
                    .filter(path -> !path.equals(logsDirectory))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparingInt((Path path) -> path.getNameCount()).reversed())
                    .toList();
            int removed = 0;
            for (Path directory : directories) {
                try {
                    if (Files.deleteIfExists(directory)) {
                        removed++;
                    }
                } catch (DirectoryNotEmptyException ignored) {
                    // Diretorio ainda possui arquivo ou subdiretorio e deve ser preservado.
                } catch (IOException exception) {
                    log.warn("Nao foi possivel remover diretorio vazio {}: {}", directory, exception.getMessage());
                }
            }
            return removed;
        } catch (IOException exception) {
            log.warn("Falha ao procurar diretorios vazios de logs em {}: {}", logsDirectory, exception.getMessage());
            return 0;
        }
    }

    private boolean temExtensaoPermitida(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSOES_PERMITIDAS.stream().anyMatch(name::endsWith);
    }

    private long tamanhoSemFalhar(Path file) {
        try {
            return Files.size(file);
        } catch (IOException exception) {
            log.warn("Nao foi possivel obter tamanho do log {}: {}", file, exception.getMessage());
            return 0L;
        }
    }

    private static FileTime ultimaModificacaoSemFalhar(Path file) {
        try {
            return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            return FileTime.from(Instant.EPOCH);
        }
    }

    public record LogRetentionResult(int totalEncontrado, int removidos, long bytesRestantes, boolean ignorado) {

        private static LogRetentionResult resultadoVazio() {
            return new LogRetentionResult(0, 0, 0L, false);
        }

        private static LogRetentionResult resultadoIgnorado() {
            return new LogRetentionResult(0, 0, 0L, true);
        }
    }
}
