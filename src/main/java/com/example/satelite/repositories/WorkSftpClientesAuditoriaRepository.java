package com.example.satelite.repositories;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.satelite.dto.auditoria.WorkSftpClienteStatusDTO;

/** Auditoria técnica de ciclos do worker, sem caminho, documento ou credencial. */
@Repository
public class WorkSftpClientesAuditoriaRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public WorkSftpClientesAuditoriaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void registrar(Ciclo ciclo) {
        String sql = """
                INSERT INTO dbo.tb_work_sftp_cliente_execucao (
                    sftp_cliente, inicio_em, fim_em, conexao, status_ciclo,
                    arquivos_validos, arquivos_rejeitados, selecionados, enviados, pendentes,
                    saldo, bloqueios, timeouts_ambiguos, duracao_ms
                ) VALUES (
                    :cliente, :inicio, :fim, :conexao, :status,
                    :validos, :rejeitados, :selecionados, :enviados, :pendentes,
                    :saldo, :bloqueios, :timeouts, :duracao
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("cliente", ciclo.cliente())
                .addValue("inicio", ciclo.inicio())
                .addValue("fim", ciclo.fim())
                .addValue("conexao", ciclo.conexao())
                .addValue("status", ciclo.status())
                .addValue("validos", ciclo.arquivosValidos())
                .addValue("rejeitados", ciclo.arquivosRejeitados())
                .addValue("selecionados", ciclo.selecionados())
                .addValue("enviados", ciclo.enviados())
                .addValue("pendentes", ciclo.pendentes())
                .addValue("saldo", ciclo.saldo())
                .addValue("bloqueios", ciclo.bloqueios())
                .addValue("timeouts", ciclo.timeoutsAmbiguos())
                .addValue("duracao", ciclo.duracaoMs()));
    }

    public List<WorkSftpClienteStatusDTO> buscarUltimosCiclos() {
        String sql = """
                WITH ultimo_ciclo AS (
                    SELECT e.*, ROW_NUMBER() OVER (
                        PARTITION BY e.sftp_cliente ORDER BY e.fim_em DESC, e.id DESC
                    ) AS posicao
                    FROM dbo.tb_work_sftp_cliente_execucao e
                )
                SELECT sftp_cliente, inicio_em, fim_em, conexao, status_ciclo,
                       arquivos_validos, arquivos_rejeitados, selecionados, enviados, pendentes,
                       saldo, bloqueios, timeouts_ambiguos, duracao_ms,
                       DATEADD(MINUTE, 30, fim_em) AS proxima_execucao_estimada
                FROM ultimo_ciclo
                WHERE posicao = 1
                ORDER BY sftp_cliente
                """;
        return jdbc.query(sql, (rs, row) -> new WorkSftpClienteStatusDTO(
                rs.getString("sftp_cliente"), data(rs, "inicio_em"), data(rs, "fim_em"),
                rs.getString("conexao"), rs.getString("status_ciclo"),
                rs.getInt("arquivos_validos"), rs.getInt("arquivos_rejeitados"),
                rs.getInt("selecionados"), rs.getInt("enviados"), rs.getInt("pendentes"),
                rs.getLong("saldo"), rs.getLong("bloqueios"), rs.getLong("timeouts_ambiguos"),
                rs.getLong("duracao_ms"), data(rs, "proxima_execucao_estimada")
        ));
    }

    public PaginaCiclos buscarHistorico(
            String cliente,
            String status,
            LocalDateTime inicio,
            LocalDateTime fimExclusivo,
            int pagina,
            int tamanho
    ) {
        List<String> filtros = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (cliente != null) {
            filtros.add("e.sftp_cliente = :cliente");
            params.addValue("cliente", cliente);
        }
        if (status != null) {
            filtros.add("e.status_ciclo = :status");
            params.addValue("status", status);
        }
        filtros.add("e.fim_em >= :inicio");
        filtros.add("e.fim_em < :fimExclusivo");
        params.addValue("inicio", inicio).addValue("fimExclusivo", fimExclusivo);
        String where = String.join(" AND ", filtros);
        long total = jdbc.queryForObject(
                "SELECT COUNT_BIG(1) FROM dbo.tb_work_sftp_cliente_execucao e WHERE " + where,
                params,
                Long.class
        );
        params.addValue("offset", Math.max(0, pagina) * tamanho).addValue("tamanho", tamanho);
        String sql = """
                SELECT e.sftp_cliente, e.inicio_em, e.fim_em, e.conexao, e.status_ciclo,
                       e.arquivos_validos, e.arquivos_rejeitados, e.selecionados, e.enviados, e.pendentes,
                       e.saldo, e.bloqueios, e.timeouts_ambiguos, e.duracao_ms,
                       DATEADD(MINUTE, 30, e.fim_em) AS proxima_execucao_estimada
                FROM dbo.tb_work_sftp_cliente_execucao e
                WHERE %s
                ORDER BY e.fim_em DESC, e.id DESC
                OFFSET :offset ROWS FETCH NEXT :tamanho ROWS ONLY
                """.formatted(where);
        return new PaginaCiclos(jdbc.query(sql, params, (rs, row) -> new WorkSftpClienteStatusDTO(
                rs.getString("sftp_cliente"), data(rs, "inicio_em"), data(rs, "fim_em"),
                rs.getString("conexao"), rs.getString("status_ciclo"),
                rs.getInt("arquivos_validos"), rs.getInt("arquivos_rejeitados"),
                rs.getInt("selecionados"), rs.getInt("enviados"), rs.getInt("pendentes"),
                rs.getLong("saldo"), rs.getLong("bloqueios"), rs.getLong("timeouts_ambiguos"),
                rs.getLong("duracao_ms"), data(rs, "proxima_execucao_estimada")
        )), total);
    }

    private LocalDateTime data(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        Timestamp valor = rs.getTimestamp(coluna);
        return valor == null ? null : valor.toLocalDateTime();
    }

    public record Ciclo(
            String cliente, LocalDateTime inicio, LocalDateTime fim, String conexao, String status,
            int arquivosValidos, int arquivosRejeitados, int selecionados, int enviados, int pendentes,
            long saldo, long bloqueios, long timeoutsAmbiguos, long duracaoMs
    ) { }

    public record PaginaCiclos(List<WorkSftpClienteStatusDTO> itens, long totalElementos) { }
}
