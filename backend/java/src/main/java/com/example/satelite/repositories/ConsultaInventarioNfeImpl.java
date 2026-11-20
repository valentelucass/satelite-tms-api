package com.example.satelite.repositories;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.dialect.SQLServerDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.data.domain.Pageable;

import com.example.satelite.models.LogIntegracaoModel;

public class ConsultaInventarioNfeImpl implements ConsultaInventarioNfe {
    private static final Pattern CHAVE = Pattern.compile("[0-9]{44}");
    private static final String ELEGIVEIS = """
            FROM dbo.tb_log_integracao l
            WHERE l.sistema_destino = 'VEDACIT' AND l.sftp_cliente = :cliente
              AND COALESCE(l.arquivado, 0) = 0 AND l.status_dados = 'SUCESSO'
              AND l.status_canhoto = 'PENDENTE_FOTO' AND l.canhoto_classificacao_operacional = 'PENDENTE_ENVIO'
              AND l.chave_cte IS NOT NULL AND TRIM(l.chave_cte) <> ''
              AND l.chave_nfe IN (SELECT j.chave FROM OPENJSON(:lote) WITH (chave varchar(44) '$') j)
            """;
    private final EntityManager entityManager;
    private final boolean sqlServer;

    public ConsultaInventarioNfeImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
        var factory = entityManager.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class);
        this.sqlServer = factory.getJdbcServices().getDialect() instanceof SQLServerDialect;
    }

    // Um lote inválido inteiro continua com a comparação Unicode original, sem truncar nenhuma chave.
    static Optional<String> loteJson(List<String> chaves) {
        if (chaves == null || chaves.size() < 32 || chaves.size() > 500) {
            return Optional.empty();
        }
        for (String chave : chaves) {
            if (chave == null || !CHAVE.matcher(chave).matches()) {
                return Optional.empty();
            }
        }
        return Optional.of("[\"" + String.join("\",\"", chaves) + "\"]");
    }

    @Override
    public Optional<List<LogIntegracaoModel>> tentarListarLoteNfe(
            String cliente, List<String> chaves, LocalDateTime agora, Pageable pagina) {
        if (!sqlServer || pagina == null || pagina.getSort().isSorted()) {
            return Optional.empty();
        }
        return loteJson(chaves).map(lote -> {
            var consulta = entityManager.unwrap(Session.class).createNativeQuery(
                    "SELECT l.* " + ELEGIVEIS + """
                    AND (l.canhoto_proxima_avaliacao_em IS NULL OR l.canhoto_proxima_avaliacao_em <= :agora)
                    ORDER BY l.data_processamento, l.id
                    """, LogIntegracaoModel.class);
            consulta.setParameter("cliente", cliente);
            consulta.setParameter("lote", lote);
            consulta.setParameter("agora", agora, LocalDateTime.class);
            if (pagina.isPaged()) {
                consulta.setFirstResult(Math.toIntExact(pagina.getOffset()));
                consulta.setMaxResults(pagina.getPageSize());
            }
            return consulta.getResultList();
        });
    }

    @Override
    public OptionalLong tentarContarLoteNfe(String cliente, List<String> chaves) {
        var lote = sqlServer ? loteJson(chaves) : Optional.<String>empty();
        if (lote.isEmpty()) {
            return OptionalLong.empty();
        }
        var consulta = entityManager.unwrap(Session.class)
                .createNativeQuery("SELECT COUNT_BIG(DISTINCT l.chave_nfe) " + ELEGIVEIS, Long.class);
        consulta.setParameter("cliente", cliente);
        consulta.setParameter("lote", lote.get());
        return OptionalLong.of(consulta.getSingleResult());
    }
}
