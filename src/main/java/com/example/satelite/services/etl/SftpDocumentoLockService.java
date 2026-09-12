package com.example.satelite.services.etl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Lock distribuído no SQL Server para impedir envio concorrente do mesmo documento SFTP. */
@Service
public class SftpDocumentoLockService {
    private static final String SQL_ADQUIRIR_LOCK = """
            DECLARE @resultado INT;
            EXEC @resultado = sp_getapplock
                @Resource = ?,
                @LockMode = 'Exclusive',
                @LockOwner = 'Session',
                @LockTimeout = ?,
                @DbPrincipal = 'public';
            SELECT @resultado;
            """;
    private static final String SQL_LIBERAR_LOCK = """
            EXEC sp_releaseapplock
                @Resource = ?,
                @LockOwner = 'Session',
                @DbPrincipal = 'public';
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ThreadLocal<Set<String>> recursosDaThread = ThreadLocal.withInitial(HashSet::new);

    @Value("${WORK_SFTP_CLIENTES_LOCK_TIMEOUT_MS:5000}")
    private long lockTimeoutMs = 5000L;

    public SftpDocumentoLockService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public <T> Optional<T> executarComLock(
            String cliente,
            String chaveNfe,
            String chaveCte,
            Supplier<T> operacao
    ) {
        String recurso = recurso(cliente, chaveNfe, chaveCte);
        if (recursosDaThread.get().contains(recurso)) return Optional.ofNullable(operacao.get());
        long timeoutSeguro = Math.max(0L, Math.min(lockTimeoutMs, 60_000L));
        return jdbcTemplate.execute((ConnectionCallback<Optional<T>>) connection -> {
            int resultado = adquirir(connection, recurso, timeoutSeguro);
            if (resultado < 0) {
                if (recursosDaThread.get().isEmpty()) recursosDaThread.remove();
                return Optional.empty();
            }
            try {
                recursosDaThread.get().add(recurso);
                return Optional.ofNullable(operacao.get());
            } finally {
                try { liberar(connection, recurso); }
                finally {
                    recursosDaThread.get().remove(recurso);
                    if (recursosDaThread.get().isEmpty()) recursosDaThread.remove();
                }
            }
        });
    }

    private int adquirir(Connection connection, String recurso, long timeoutMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL_ADQUIRIR_LOCK)) {
            statement.setString(1, recurso);
            statement.setLong(2, timeoutMs);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return -999;
                }
                return resultSet.getInt(1);
            }
        }
    }

    private void liberar(Connection connection, String recurso) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL_LIBERAR_LOCK)) {
            statement.setString(1, recurso);
            statement.execute();
        }
    }

    static String recurso(String cliente, String chaveNfe, String chaveCte) {
        String clienteSeguro = normalizarCliente(cliente);
        validarChave(chaveNfe, "NF-e");
        validarChave(chaveCte, "CT-e");
        // O mesmo documento Vedacit não pode concorrer por vir de outro perfil/canal.
        if ("VEDACIT_XML".equals(clienteSeguro)) return "SATELITE_TMS:VEDACIT:XML:" + chaveCte;
        return "SATELITE_TMS:VEDACIT:COMPROVANTE:" + chaveNfe + ":" + chaveCte;
    }

    private static String normalizarCliente(String cliente) {
        String valor = cliente == null ? "" : cliente.trim().toUpperCase(Locale.ROOT);
        if (!valor.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("Identificador de cliente SFTP inválido");
        }
        return valor;
    }

    private static void validarChave(String chave, String tipo) {
        if (chave == null || !chave.matches("\\d{44}")) {
            throw new IllegalArgumentException("Chave " + tipo + " inválida para lock SFTP");
        }
    }
}
