package com.example.satelite.repositories;

import static com.example.satelite.dto.auditoria.ReconciliacaoLocalDTO.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Histórico técnico separado do fluxo Vedacit; nunca altera o estado dos documentos. */
@Repository
public class ReconciliacaoLocalRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public ReconciliacaoLocalRepository(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
    }
    public void validarEstrutura() {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys.tables WHERE object_id IN
                (OBJECT_ID('dbo.tb_reconciliacao_cliente_execucao'),OBJECT_ID('dbo.tb_reconciliacao_cliente_item'))
                """, Map.of(), Integer.class);
        if (n == null || n != 2) throw new IllegalStateException("MIGRACAO_V29_PENDENTE");
    }
    private static String clienteValido(String cliente) {
        if (!Set.of("PPG", "SELIA", "SUPPORTE").contains(cliente)) throw new IllegalArgumentException("CLIENTE_LOCAL_INVALIDO");
        return cliente;
    }
    /** O adaptador mantém lock distribuído por cliente durante esta operação e a página. */
    public Optional<Execucao> abrirOuRetomar(String cliente, LocalDate dia, LocalDateTime agora) {
        var p = new MapSqlParameterSource("cliente", clienteValido(cliente)).addValue("dia", dia).addValue("agora", agora);
        var abertas = jdbc.query("""
                SELECT TOP (1) * FROM dbo.tb_reconciliacao_cliente_execucao
                WHERE cliente=:cliente AND fim_em IS NULL AND dia_referencia<=:dia ORDER BY dia_referencia,id
                """, p, (r,n) -> execucao(r));
        if (!abertas.isEmpty()) return Optional.of(abertas.get(0));
        jdbc.update("""
                INSERT INTO dbo.tb_reconciliacao_cliente_execucao
                    (cliente,dia_referencia,inicio_em,atualizado_em,status,limite_log_id)
                SELECT :cliente,:dia,:agora,:agora,'EM_EXECUCAO',COALESCE(MAX(id),0)
                FROM dbo.tb_log_integracao WHERE sistema_destino=:cliente
                HAVING NOT EXISTS (SELECT 1 FROM dbo.tb_reconciliacao_cliente_execucao WHERE cliente=:cliente AND dia_referencia=:dia)
                """, p);
        return jdbc.query("""
                SELECT * FROM dbo.tb_reconciliacao_cliente_execucao WHERE cliente=:cliente AND dia_referencia=:dia AND fim_em IS NULL
                """, p, (r,n) -> execucao(r)).stream().findFirst();
    }
    public List<Candidato> pagina(Execucao e, int tamanho) {
        return jdbc.query("""
                SELECT TOP (:tamanho) id,arquivado,status_dados,status_canhoto,data_processamento_dados,data_processamento_canhoto
                FROM dbo.tb_log_integracao WHERE sistema_destino=:cliente AND id>:ultimo AND id<=:limite
                ORDER BY id
                """, new MapSqlParameterSource("cliente", clienteValido(e.cliente())).addValue("ultimo", e.ultimoId())
                .addValue("limite", e.limiteId()).addValue("tamanho", Math.max(1, Math.min(200, tamanho))),
                (r,n) -> new Candidato(r.getLong("id"), r.getBoolean("arquivado"), r.getString("status_dados"),
                        r.getString("status_canhoto"), data(r,"data_processamento_dados"), data(r,"data_processamento_canhoto")));
    }
    public void registrar(Execucao e, Candidato c, Revisao r, LocalDateTime agora, LocalDateTime proxima) {
        var p = new MapSqlParameterSource("id",e.id()).addValue("cliente",clienteValido(e.cliente())).addValue("log",c.id())
                .addValue("arquivado",c.arquivado()).addValue("dados",r.dados()).addValue("pod",r.comprovante())
                .addValue("acao",r.acao()).addValue("agora",agora).addValue("proxima",proxima);
        tx.executeWithoutResult(t -> {
            int inseridos = jdbc.update("""
                    INSERT INTO dbo.tb_reconciliacao_cliente_item
                        (execucao_id,log_id,revisado_em,proxima_revisao_em,arquivado,resultado_dados,resultado_comprovante,acao)
                    SELECT e.id,l.id,:agora,:proxima,:arquivado,:dados,:pod,:acao
                    FROM dbo.tb_reconciliacao_cliente_execucao e
                    JOIN dbo.tb_log_integracao l ON l.sistema_destino=e.cliente AND l.id=:log
                    WHERE e.id=:id AND e.cliente=:cliente AND e.fim_em IS NULL AND l.id<=e.limite_log_id
                      AND NOT EXISTS (SELECT 1 FROM dbo.tb_reconciliacao_cliente_item WHERE execucao_id=:id AND log_id=:log)
                    """, p);
            if (inseridos == 0) {
                Integer existente = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM dbo.tb_reconciliacao_cliente_item i
                        JOIN dbo.tb_reconciliacao_cliente_execucao e ON e.id=i.execucao_id
                        WHERE i.execucao_id=:id AND i.log_id=:log AND e.cliente=:cliente
                        """,p,Integer.class);
                if (existente == null || existente != 1) throw new IllegalStateException("ITEM_FORA_DA_EXECUCAO");
            }
            jdbc.update("""
                    UPDATE dbo.tb_reconciliacao_cliente_execucao SET ultimo_log_id=:log,atualizado_em=:agora,status='EM_EXECUCAO'
                    WHERE id=:id AND cliente=:cliente AND fim_em IS NULL AND ultimo_log_id<:log
                    """,p);
        });
    }
    public void concluir(Execucao e, LocalDateTime agora) {
        jdbc.update("""
                UPDATE dbo.tb_reconciliacao_cliente_execucao SET status='CONCLUIDA',fim_em=:agora,atualizado_em=:agora
                WHERE id=:id AND cliente=:cliente AND fim_em IS NULL
                """,Map.of("id",e.id(),"cliente",e.cliente(),"agora",agora));
    }
    public List<Map<String,Object>> execucoes(String cliente) {
        return jdbc.queryForList("""
                SELECT TOP (30) e.*, (SELECT COUNT_BIG(*) FROM dbo.tb_reconciliacao_cliente_item i WHERE i.execucao_id=e.id) revisados
                FROM dbo.tb_reconciliacao_cliente_execucao e WHERE cliente=:cliente ORDER BY id DESC
                """,Map.of("cliente",clienteValido(cliente)));
    }
    public List<Map<String,Object>> resumo(String cliente, long id) {
        return jdbc.queryForList("""
                SELECT i.resultado_dados,i.resultado_comprovante,i.acao,COUNT_BIG(*) quantidade
                FROM dbo.tb_reconciliacao_cliente_item i JOIN dbo.tb_reconciliacao_cliente_execucao e ON e.id=i.execucao_id
                WHERE e.id=:id AND e.cliente=:cliente GROUP BY i.resultado_dados,i.resultado_comprovante,i.acao
                """,Map.of("cliente",clienteValido(cliente),"id",id));
    }
    private static Execucao execucao(ResultSet r) throws SQLException {
        return new Execucao(r.getLong("id"),r.getString("cliente"),r.getDate("dia_referencia").toLocalDate(),
                r.getLong("ultimo_log_id"),r.getLong("limite_log_id"));
    }
    private static LocalDateTime data(ResultSet r,String coluna) throws SQLException {
        var t=r.getTimestamp(coluna); return t==null ? null : t.toLocalDateTime();
    }
}
