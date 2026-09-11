package com.example.satelite.repositories;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    /** Falha antes de qualquer envio quando o pacote foi trocado sem a migration. */
    public void validarEstrutura() {
        Integer colunas = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys.columns
                WHERE object_id = OBJECT_ID(N'dbo.tb_work_sftp_cliente_execucao')
                  AND name IN ('xml_habilitado', 'xml_avaliados', 'xml_enviados', 'xml_ja_processados',
                               'xml_pendentes', 'xml_erros', 'erros_comprovante', 'motivo_falha', 'execucao_id', 'atualizado_em')
                """, new MapSqlParameterSource(), Integer.class);
        if (colunas == null || colunas != 10) throw new IllegalStateException("MIGRACAO_V23_PENDENTE");
    }

    public void registrar(Ciclo ciclo) {
        gravar(null, ciclo);
    }

    /** A mesma execução recebe parciais e fechamento; não cria um ciclo por turno. */
    public void registrarProgresso(UUID execucaoId, Ciclo ciclo) {
        gravar(java.util.Objects.requireNonNull(execucaoId), ciclo);
    }

    private void gravar(UUID execucaoId, Ciclo ciclo) {
        String sql = """
                INSERT INTO dbo.tb_work_sftp_cliente_execucao (
                    sftp_cliente, inicio_em, fim_em, conexao, status_ciclo,
                    arquivos_validos, arquivos_rejeitados, selecionados, enviados, pendentes,
                    saldo, bloqueios, timeouts_ambiguos, duracao_ms, xml_habilitado, xml_avaliados, xml_enviados, xml_ja_processados, xml_pendentes, xml_erros, erros_comprovante, motivo_falha, execucao_id, atualizado_em
                ) VALUES (
                    :cliente, :inicio, :fim, :conexao, :status,
                    :validos, :rejeitados, :selecionados, :enviados, :pendentes,
                    :saldo, :bloqueios, :timeouts, :duracao, :xmlHabilitado, :xmlAvaliados, :xmlEnviados, :xmlJaProcessados, :xmlPendentes, :xmlErros, :errosComprovante, :motivoFalha, :execucaoId, :atualizado
                )
                """;
        if (execucaoId != null) sql = """
                UPDATE dbo.tb_work_sftp_cliente_execucao SET
                    fim_em=:fim, conexao=:conexao, status_ciclo=:status,
                    arquivos_validos=:validos, arquivos_rejeitados=:rejeitados,
                    selecionados=:selecionados, enviados=:enviados, pendentes=:pendentes,
                    saldo=:saldo, bloqueios=:bloqueios, timeouts_ambiguos=:timeouts, duracao_ms=:duracao,
                    xml_habilitado=:xmlHabilitado, xml_avaliados=:xmlAvaliados, xml_enviados=:xmlEnviados,
                    xml_ja_processados=:xmlJaProcessados, xml_pendentes=:xmlPendentes, xml_erros=:xmlErros,
                    erros_comprovante=:errosComprovante, motivo_falha=:motivoFalha, atualizado_em=:atualizado
                WHERE execucao_id=:execucaoId AND fim_em IS NULL;
                IF NOT EXISTS (SELECT 1 FROM dbo.tb_work_sftp_cliente_execucao WHERE execucao_id=:execucaoId)
                """ + sql;
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
                .addValue("duracao", ciclo.duracaoMs())
                .addValue("xmlHabilitado", ciclo.xmlHabilitado())
                .addValue("xmlAvaliados", ciclo.xmlAvaliados())
                .addValue("xmlEnviados", ciclo.xmlEnviados())
                .addValue("xmlJaProcessados", ciclo.xmlJaProcessados())
                .addValue("xmlPendentes", ciclo.xmlPendentes())
                .addValue("xmlErros", ciclo.xmlErros())
                .addValue("errosComprovante", ciclo.errosComprovante())
                .addValue("motivoFalha", ciclo.motivoFalha())
                .addValue("execucaoId", execucaoId == null ? null : execucaoId.toString())
                .addValue("atualizado", LocalDateTime.now()));
    }

    public List<WorkSftpClienteStatusDTO> buscarUltimosCiclos() {
        String sql = """
                WITH ultimo_ciclo AS (
                    SELECT e.*, ROW_NUMBER() OVER (
                        PARTITION BY e.sftp_cliente ORDER BY e.inicio_em DESC, e.id DESC
                    ) AS posicao
                    FROM dbo.tb_work_sftp_cliente_execucao e
                )
                SELECT sftp_cliente, inicio_em, fim_em, atualizado_em, conexao, status_ciclo,
                       arquivos_validos, arquivos_rejeitados, selecionados, enviados, pendentes,
                       saldo, bloqueios, timeouts_ambiguos, duracao_ms, xml_habilitado, xml_avaliados, xml_enviados, xml_ja_processados, xml_pendentes, xml_erros, erros_comprovante, motivo_falha,
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
                rs.getLong("duracao_ms"), data(rs, "proxima_execucao_estimada"),
                (Boolean) rs.getObject("xml_habilitado"), (Integer) rs.getObject("xml_avaliados"), (Integer) rs.getObject("xml_enviados"), (Integer) rs.getObject("xml_ja_processados"), (Integer) rs.getObject("xml_pendentes"), (Integer) rs.getObject("xml_erros"), (Integer) rs.getObject("erros_comprovante"), rs.getString("motivo_falha"), data(rs, "atualizado_em")
        ));
    }

    public PaginaCiclos buscarHistorico(
            String cliente,
            String status,
            LocalDateTime inicio,
            LocalDateTime fimExclusivo,
            int pagina,
            int tamanho,
            String origem
    ) {
        List<String> filtros = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        // A origem abaixo identifica somente comprovantes; a etapa XML pode usar fallback ESL.
        // A mesma restrição vale para a página e para sua contagem total.
        if (origem != null) {
            filtros.add(":origem = 'SFTP'");
            params.addValue("origem", origem);
        }
        if (cliente != null) {
            filtros.add("e.sftp_cliente = :cliente");
            params.addValue("cliente", cliente);
        }
        if (status != null) {
            filtros.add("e.status_ciclo = :status");
            params.addValue("status", status);
        }
        filtros.add("((e.fim_em >= :inicio AND e.fim_em < :fimExclusivo) OR (e.fim_em IS NULL AND e.inicio_em >= :inicio AND e.inicio_em < :fimExclusivo))");
        params.addValue("inicio", inicio).addValue("fimExclusivo", fimExclusivo);
        String where = String.join(" AND ", filtros);
        long total = jdbc.queryForObject(
                "SELECT COUNT_BIG(1) FROM dbo.tb_work_sftp_cliente_execucao e WHERE " + where,
                params,
                Long.class
        );
        params.addValue("offset", Math.max(0, pagina) * tamanho).addValue("tamanho", tamanho);
        String sql = """
                SELECT e.sftp_cliente, e.inicio_em, e.fim_em, e.atualizado_em, e.conexao, e.status_ciclo,
                       e.arquivos_validos, e.arquivos_rejeitados, e.selecionados, e.enviados, e.pendentes,
                       e.saldo, e.bloqueios, e.timeouts_ambiguos, e.duracao_ms, e.xml_habilitado, e.xml_avaliados, e.xml_enviados, e.xml_ja_processados, e.xml_pendentes, e.xml_erros, e.erros_comprovante, e.motivo_falha,
                       DATEADD(MINUTE, 30, e.fim_em) AS proxima_execucao_estimada
                FROM dbo.tb_work_sftp_cliente_execucao e
                WHERE %s
                ORDER BY e.inicio_em DESC, e.id DESC
                OFFSET :offset ROWS FETCH NEXT :tamanho ROWS ONLY
                """.formatted(where);
        return new PaginaCiclos(jdbc.query(sql, params, (rs, row) -> new WorkSftpClienteStatusDTO(
                rs.getString("sftp_cliente"), data(rs, "inicio_em"), data(rs, "fim_em"),
                rs.getString("conexao"), rs.getString("status_ciclo"),
                rs.getInt("arquivos_validos"), rs.getInt("arquivos_rejeitados"),
                rs.getInt("selecionados"), rs.getInt("enviados"), rs.getInt("pendentes"),
                rs.getLong("saldo"), rs.getLong("bloqueios"), rs.getLong("timeouts_ambiguos"),
                rs.getLong("duracao_ms"), data(rs, "proxima_execucao_estimada"),
                (Boolean) rs.getObject("xml_habilitado"), (Integer) rs.getObject("xml_avaliados"), (Integer) rs.getObject("xml_enviados"), (Integer) rs.getObject("xml_ja_processados"), (Integer) rs.getObject("xml_pendentes"), (Integer) rs.getObject("xml_erros"), (Integer) rs.getObject("erros_comprovante"), rs.getString("motivo_falha"), data(rs, "atualizado_em")
        )), total);
    }

    private LocalDateTime data(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        Timestamp valor = rs.getTimestamp(coluna);
        return valor == null ? null : valor.toLocalDateTime();
    }

    public record Ciclo(
            String cliente, LocalDateTime inicio, LocalDateTime fim, String conexao, String status,
            int arquivosValidos, int arquivosRejeitados, int selecionados, int enviados, int pendentes,
            long saldo, long bloqueios, long timeoutsAmbiguos, long duracaoMs,
            Boolean xmlHabilitado, Integer xmlAvaliados, Integer xmlEnviados, Integer xmlJaProcessados, Integer xmlPendentes, Integer xmlErros, Integer errosComprovante, String motivoFalha
    ) {
        public Ciclo(String cliente, LocalDateTime inicio, LocalDateTime fim, String conexao, String status,
                int arquivosValidos, int arquivosRejeitados, int selecionados, int enviados, int pendentes,
                long saldo, long bloqueios, long timeoutsAmbiguos, long duracaoMs) {
            this(cliente, inicio, fim, conexao, status, arquivosValidos, arquivosRejeitados, selecionados, enviados,
                    pendentes, saldo, bloqueios, timeoutsAmbiguos, duracaoMs, null, null, null, null, null, null, null, null);
        }
    }

    public record PaginaCiclos(List<WorkSftpClienteStatusDTO> itens, long totalElementos) { }
}
