package com.example.satelite.services.etl;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lock distribuído no SQL Server para impedir envio concorrente do mesmo documento SFTP. */
@Service
public class SftpDocumentoLockService {
    private final JdbcTemplate jdbcTemplate;

    public SftpDocumentoLockService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public <T> Optional<T> executarComLock(
            String cliente,
            String chaveNfe,
            String chaveCte,
            Supplier<T> operacao
    ) {
        String recurso = recurso(cliente, chaveNfe, chaveCte);
        Integer resultado = jdbcTemplate.queryForObject("""
                DECLARE @resultado INT;
                EXEC @resultado = sp_getapplock
                    @Resource = ?,
                    @LockMode = 'Exclusive',
                    @LockOwner = 'Transaction',
                    @LockTimeout = 0,
                    @DbPrincipal = 'public';
                SELECT @resultado;
                """, Integer.class, recurso);
        if (resultado == null || resultado < 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(operacao.get());
    }

    static String recurso(String cliente, String chaveNfe, String chaveCte) {
        String clienteSeguro = normalizarCliente(cliente);
        validarChave(chaveNfe, "NF-e");
        validarChave(chaveCte, "CT-e");
        return "SATELITE_TMS:VEDACIT:SFTP:" + clienteSeguro + ":" + chaveNfe + ":" + chaveCte;
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
