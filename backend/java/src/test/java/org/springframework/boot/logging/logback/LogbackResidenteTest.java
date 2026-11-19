package org.springframework.boot.logging.logback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.mock.env.MockEnvironment;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;

// O configurador usado pelo Spring Boot tem acesso de pacote. Cada papel recebe um contexto de teste independente.
class LogbackResidenteTest {
    @TempDir Path pasta;

    @Test
    void rotacionaPorTamanhoEDiaEIsolaPapeisDuranteEscritaSimultanea() throws Exception {
        var clientes = new LoggerContext();
        var api = new LoggerContext();
        try {
            configurar(clientes, "clientes");
            configurar(api, "api-integracoes");
            api.getLogger("com.example.satelite").info("APENAS-API");
            clientes.getLogger("satelite.vedacit.sftp.detail").info("DETALHE-SFTP {}", "x".repeat(2000));
            clientes.getLogger("satelite.internal.detail").info("DETALHE-INTERNO");
            Path historico = pasta.resolve("clientes/historico");
            assertEquals(1, arquivos(historico).size());
            assertTrue(Files.readString(arquivos(historico).get(0)).contains("DETALHE-SFTP"));

            var appender = (RollingFileAppender<?>) clientes.getLogger("satelite.internal.detail").getAppender("FILE");
            var politica = (SizeAndTimeBasedRollingPolicy<?>) appender.getRollingPolicy();
            politica.getTimeBasedFileNamingAndTriggeringPolicy().setCurrentTime(
                    System.currentTimeMillis() + Duration.ofDays(1).toMillis());
            clientes.getLogger("com.example.satelite").info("NOVO-DIA-CLIENTES");
            assertEquals(2, arquivos(historico).size());
            assertTrue(Files.readString(arquivos(historico).get(1)).contains("DETALHE-INTERNO"));

            String atual = Files.readString(pasta.resolve("clientes/atual.log"));
            assertTrue(atual.contains("NOVO-DIA-CLIENTES"));
            assertFalse(atual.contains("APENAS-API"));
            String atualApi = Files.readString(pasta.resolve("api-integracoes/atual.log"));
            assertTrue(atualApi.contains("APENAS-API"));
            assertFalse(atualApi.contains("NOVO-DIA-CLIENTES"));
        } finally {
            clientes.stop();
            api.stop();
        }
        // No Windows esta operação também verifica que a rotação/encerramento liberou os arquivos.
        Files.move(pasta.resolve("clientes"), pasta.resolve("clientes-encerrado"));
        Files.move(pasta.resolve("api-integracoes"), pasta.resolve("api-encerrada"));
    }

    private void configurar(LoggerContext contexto, String papel) throws Exception {
        var ambiente = new MockEnvironment().withProperty("satelite.logs.papel", papel)
                .withProperty("satelite.logs.diretorio", pasta.toString())
                .withProperty("satelite.logs.tamanho-arquivo", "1KB");
        contexto.setMDCAdapter(new LogbackMDCAdapter());
        var configurador = new SpringBootJoranConfigurator(new LoggingInitializationContext(ambiente));
        configurador.setContext(contexto);
        configurador.doConfigure(getClass().getResource("/logback-residente.xml"));
        contexto.start();
    }

    private List<Path> arquivos(Path caminho) throws IOException {
        try (var arquivos = Files.list(caminho)) {
            return arquivos.sorted().toList();
        }
    }
}
