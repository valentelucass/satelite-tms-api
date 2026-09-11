import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.example.satelite.repositories.IntegracaoIndicadoresEtapasRepository;
import tools.jackson.databind.ObjectMapper;

/** Executa as consultas reais, com a barreira SELECT/rollback da sonda existente. */
public final class SqlIndicadoresEtapasReadOnlyProbe {
    public static void main(String[] args) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, String> env = new HashMap<>();
            for (String line : Files.readAllLines(Path.of(args[0]))) {
                int eq = line.indexOf('=');
                if (eq < 1 || line.stripLeading().startsWith("#")) continue;
                String value = line.substring(eq + 1).trim();
                if (value.length() > 1 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
                env.put(line.substring(0, eq).trim(), value);
            }
            String url = env.getOrDefault("SATELITE_DB_URL", env.get("DB_URL"));
            SqlSftpReadOnlyProbe.require(url != null && url.matches(
                    "(?is)^jdbc:sqlserver://[^;]+;.*databaseName=SATELITE_TMS_AUDITORIA(?:;.*)?$"), "DATABASE_OUT_OF_SCOPE");
            var props = new Properties();
            props.setProperty("user", env.getOrDefault("SATELITE_DB_USER", env.get("DB_USER")));
            props.setProperty("password", env.getOrDefault("SATELITE_DB_PASSWORD", env.get("DB_PASSWORD")));
            props.setProperty("loginTimeout", "10");
            props.setProperty("applicationName", "ReadOnly-Indicadores-Etapas");
            try (Connection raw = DriverManager.getConnection(url, props)) {
                SqlSftpReadOnlyProbe.require("SATELITE_TMS_AUDITORIA".equalsIgnoreCase(raw.getCatalog()), "DATABASE_OUT_OF_SCOPE");
                raw.setAutoCommit(false);
                raw.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                var connection = SqlSftpReadOnlyProbe.guard(raw);
                try {
                    try (var statement = connection.createStatement()) { statement.execute("SET LOCK_TIMEOUT 1500"); }
                    var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                    jdbc.setQueryTimeout(15);
                    var dto = new IntegracaoIndicadoresEtapasRepository(new NamedParameterJdbcTemplate(jdbc))
                            .consultar(LocalDate.parse(args[1]), LocalDate.parse(args[2]), List.of("VEDACIT"));
                    result.put("indicadores", dto);
                    for (String etapa : List.of("DADOS", "COMPROVANTE")) {
                        long resumo = dto.etapas().stream().filter(e -> etapa.equals(e.etapa())).mapToLong(e -> e.sucessosPeriodo()).sum();
                        long dias = dto.evolucao().stream().filter(e -> etapa.equals(e.etapa())).mapToLong(e -> e.sucessos()).sum();
                        long errosResumo = dto.etapas().stream().filter(e -> etapa.equals(e.etapa())).mapToLong(e -> e.falhasPeriodo()).sum();
                        long errosDias = dto.evolucao().stream().filter(e -> etapa.equals(e.etapa())).mapToLong(e -> e.falhas()).sum();
                        SqlSftpReadOnlyProbe.require(resumo == dias && errosResumo == errosDias, "SUMMARY_DAILY_MISMATCH");
                    }
                    Long xmlIndependente = jdbc.queryForObject("""
                            SELECT COUNT_BIG(*) FROM (
                                SELECT COALESCE(NULLIF(chave_cte, ''), CONCAT('LOG:', id)) AS documento,
                                       MIN(data_processamento_dados) AS primeira_confirmacao
                                FROM dbo.tb_log_integracao
                                WHERE sistema_destino = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
                                  AND UPPER(TRIM(status_dados)) IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
                                  AND data_processamento_dados IS NOT NULL
                                GROUP BY COALESCE(NULLIF(chave_cte, ''), CONCAT('LOG:', id))
                            ) confirmados WHERE primeira_confirmacao >= ? AND primeira_confirmacao < ?
                            """, Long.class, LocalDate.parse(args[1]).atStartOfDay(), LocalDate.parse(args[2]).plusDays(1).atStartOfDay());
                    long xml = dto.etapas().stream().filter(e -> e.etapa().equals("DADOS")).mapToLong(e -> e.sucessosPeriodo()).sum();
                    SqlSftpReadOnlyProbe.require(xmlIndependente != null && xml == xmlIndependente, "XML_INDEPENDENT_SQL_MISMATCH");
                    result.put("resumo_serie_coincidem", true);
                    result.put("xml_sql_independente_coincide", true);
                } finally { connection.rollback(); result.put("rollback", true); }
            }
            result.put("status", "PASS");
        } catch (Exception e) {
            result.put("status", "FAIL");
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            result.put("error_type", cause.getClass().getSimpleName());
            if (cause instanceof SQLException sql) result.put("sql_error_number", sql.getErrorCode());
        }
        result.put("database", "SATELITE_TMS_AUDITORIA");
        result.put("executed_queries", SqlSftpReadOnlyProbe.queries);
        Files.writeString(Path.of(args[3]), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println(result.get("status"));
        if (!"PASS".equals(result.get("status"))) System.exit(1);
    }
}
