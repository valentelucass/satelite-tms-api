package com.example.satelite.repositories;

import static com.example.satelite.dto.auditoria.ReconciliacaoVedacitDTO.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Repository
public class ReconciliacaoVedacitRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public ReconciliacaoVedacitRepository(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
    }

    public void validarEstrutura() {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys.tables WHERE object_id IN
                (OBJECT_ID('dbo.tb_reconciliacao_vedacit_execucao'),OBJECT_ID('dbo.tb_reconciliacao_vedacit_item'))
                """, Map.of(), Integer.class);
        if (n == null || n != 2) throw new IllegalStateException("MIGRACAO_V27_PENDENTE");
    }

    /** Chamado sob lock global de sessao. Retoma primeiro uma revisao incompleta, mesmo de outra noite. */
    public Optional<Execucao> abrirOuRetomar(LocalDate dia, LocalDateTime agora) {
        var p = new MapSqlParameterSource("dia", dia).addValue("agora", agora);
        jdbc.update("""
                UPDATE dbo.tb_reconciliacao_vedacit_execucao SET bloqueio_sftp=NULL,bloqueio_consulta_xml=NULL,
                    bloqueio_consulta_pod=NULL,bloqueio_origem_xml=NULL,bloqueio_envio_xml=NULL,bloqueio_envio_pod=NULL,fontes_dia=:dia
                WHERE fim_em IS NULL AND fontes_dia<:dia
                """,p);
        var abertas = jdbc.query("""
                SELECT TOP (1) * FROM dbo.tb_reconciliacao_vedacit_execucao
                WHERE fim_em IS NULL AND dia_referencia<=:dia ORDER BY dia_referencia,id
                """, p, (rs,n) -> execucao(rs));
        if (!abertas.isEmpty()) return Optional.of(abertas.get(0));
        jdbc.update("""
                INSERT INTO dbo.tb_reconciliacao_vedacit_execucao
                    (dia_referencia,inicio_em,atualizado_em,status,limite_log_id,fontes_dia)
                SELECT :dia,:agora,:agora,'EM_EXECUCAO',COALESCE(MAX(id),0),:dia
                FROM dbo.tb_log_integracao WHERE sistema_destino='VEDACIT'
                HAVING NOT EXISTS (SELECT 1 FROM dbo.tb_reconciliacao_vedacit_execucao WHERE dia_referencia=:dia)
                """, p);
        return jdbc.query("""
                SELECT * FROM dbo.tb_reconciliacao_vedacit_execucao WHERE dia_referencia=:dia AND fim_em IS NULL
                """, p, (rs,n) -> execucao(rs)).stream().findFirst();
    }

    /** Varredura finita por ID. Inclui arquivados para conferencia, jamais para reenvio. */
    public List<Candidato> pagina(Execucao e, int tamanho) {
        return jdbc.query("""
                SELECT TOP (:tamanho) l.id,l.chave_nfe,l.chave_cte,
                    COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte) cte_efetivo,
                    l.sftp_cliente,l.arquivado,l.status_dados,l.status_canhoto,
                    l.canhoto_classificacao_operacional,l.data_processamento_dados,l.mensagem_erro_dados,
                    l.tentativas_dados,l.canhoto_referencia,
                    x.confirmado_em xml_confirmado_em,x.tem_sucesso xml_sucesso,
                    c.log_origem_id pod_confirmado,c.primeira_confirmacao_em,c.data_confiavel
                FROM dbo.tb_log_integracao l
                OUTER APPLY (
                    SELECT MIN(h.data_processamento_dados) confirmado_em,COUNT_BIG(*) tem_sucesso
                    FROM dbo.tb_log_integracao h
                    WHERE h.sistema_destino='VEDACIT' AND h.chave_cte=l.chave_cte AND h.status_dados='SUCESSO'
                ) x
                LEFT JOIN dbo.tb_confirmacao_comprovante c ON c.chave_nfe=l.chave_nfe
                    AND c.chave_cte=COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte)
                WHERE l.sistema_destino='VEDACIT' AND l.id>:ultimo AND l.id<=:limite
                  AND (l.status LIKE 'ERRO%' OR l.status_dados LIKE 'ERRO%' OR l.status_canhoto LIKE 'ERRO%'
                    OR l.status IN ('PARCIAL','PENDENTE_FOTO')
                    OR l.status_dados IN ('ERRO_DESTINO','PENDENTE_ORIGEM')
                    OR (l.status_dados='SUCESSO' AND l.data_processamento_dados IS NULL)
                    OR l.status_canhoto IN ('ERRO_DESTINO','PENDENTE_FOTO')
                    OR l.canhoto_classificacao_operacional IN ('PENDENTE_ENVIO','PENDENTE_TECNICO',
                        'BLOQUEADO_ORIGEM','BLOQUEADO_DESTINO','TIMEOUT_AMBIGUO')
                    OR (l.status_canhoto='SUCESSO' AND (c.data_confiavel=0 OR c.log_origem_id IS NULL)))
                ORDER BY l.id
                """, new MapSqlParameterSource("tamanho", tamanho).addValue("ultimo", e.ultimoId())
                        .addValue("limite", e.limiteId()), (r,n) -> new Candidato(r.getLong("id"),r.getString("chave_nfe"),
                        r.getString("chave_cte"),r.getString("cte_efetivo"),r.getString("sftp_cliente"),r.getBoolean("arquivado"),
                        r.getString("status_dados"),r.getString("status_canhoto"),r.getString("canhoto_classificacao_operacional"),
                        data(r,"data_processamento_dados"),r.getString("mensagem_erro_dados"),r.getInt("tentativas_dados"),
                        data(r,"xml_confirmado_em"),r.getLong("xml_sucesso")>0,r.getObject("pod_confirmado")!=null,
                        data(r,"primeira_confirmacao_em"),r.getBoolean("data_confiavel"),r.getString("canhoto_referencia")));
    }

    /** Evidencia e checkpoint sao atomicos: queda nunca pula um item ainda sem auditoria. */
    public void registrar(Execucao e, Candidato c, Revisao r, LocalDateTime agora, LocalDateTime proxima) {
        var p = new MapSqlParameterSource("execucao",e.id()).addValue("log",c.id()).addValue("agora",agora)
                .addValue("proxima",proxima).addValue("arquivado",c.arquivado()).addValue("xml",r.xml())
                .addValue("pod",r.comprovante()).addValue("acao",r.acao()).addValue("detalhe",r.detalhe());
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO dbo.tb_reconciliacao_vedacit_item
                        (execucao_id,log_id,revisado_em,proxima_revisao_em,arquivado,resultado_xml,resultado_comprovante,acao,detalhe)
                    SELECT :execucao,:log,:agora,:proxima,:arquivado,:xml,:pod,:acao,:detalhe
                    WHERE NOT EXISTS (SELECT 1 FROM dbo.tb_reconciliacao_vedacit_item WHERE execucao_id=:execucao AND log_id=:log)
                    """,p);
            jdbc.update("""
                    UPDATE dbo.tb_reconciliacao_vedacit_execucao SET ultimo_log_id=:log,atualizado_em=:agora,
                        status='EM_EXECUCAO',motivo=NULL WHERE id=:execucao AND fim_em IS NULL AND ultimo_log_id<:log
                    """,p);
        });
    }

    public void bloquearFonte(long id, String fonte, String motivo) {
        String coluna = switch (fonte) {
            case "SFTP" -> "bloqueio_sftp"; case "CONSULTA_XML" -> "bloqueio_consulta_xml";
            case "CONSULTA_POD" -> "bloqueio_consulta_pod"; case "ORIGEM_XML" -> "bloqueio_origem_xml";
            case "ENVIO_XML" -> "bloqueio_envio_xml"; case "ENVIO_POD" -> "bloqueio_envio_pod";
            default -> throw new IllegalArgumentException("Fonte de reconciliacao invalida");
        };
        jdbc.update("UPDATE dbo.tb_reconciliacao_vedacit_execucao SET " + coluna + "=:motivo WHERE id=:id AND fim_em IS NULL",
                Map.of("id",id,"motivo",motivo));
    }

    public boolean xmlRetidoPorOutroHistorico(String cte, long id) {
        Long n=jdbc.queryForObject("""
                SELECT COUNT_BIG(*) FROM dbo.tb_log_integracao WHERE sistema_destino='VEDACIT'
                  AND chave_cte=:cte AND id<>:id AND status_dados='ERRO_DESTINO'
                  AND (mensagem_erro_dados IS NULL OR mensagem_erro_dados NOT LIKE 'ORIGEM_XML_%')
                """,Map.of("cte",cte,"id",id),Long.class);
        return n!=null && n>0;
    }
    public void concluir(long id, LocalDateTime agora) {
        jdbc.update("""
                UPDATE dbo.tb_reconciliacao_vedacit_execucao SET status='CONCLUIDA',fim_em=:agora,atualizado_em=:agora,motivo=NULL
                WHERE id=:id AND fim_em IS NULL
                """,Map.of("id",id,"agora",agora));
    }
    public void falha(long id, LocalDateTime agora) {
        jdbc.update("""
                UPDATE dbo.tb_reconciliacao_vedacit_execucao SET status='AGUARDANDO_RETOMADA',atualizado_em=:agora,
                    retomadas=retomadas+1,motivo='Revisao interrompida; checkpoint preservado para retomada'
                WHERE id=:id AND fim_em IS NULL
                """,Map.of("id",id,"agora",agora));
    }
    public void pausar(long id, LocalDateTime agora) {
        jdbc.update("""
                UPDATE dbo.tb_reconciliacao_vedacit_execucao SET status='AGUARDA_PROXIMA_MADRUGADA',atualizado_em=:agora,
                    motivo='Fim da janela; documentos restantes continuam do checkpoint na proxima madrugada'
                WHERE id=:id AND fim_em IS NULL
                """,Map.of("id",id,"agora",agora));
    }
    public List<Resumo> resumo(long id) {
        return jdbc.query("""
                SELECT 'XML' etapa,resultado_xml resultado,COUNT_BIG(*) quantidade
                FROM dbo.tb_reconciliacao_vedacit_item WHERE execucao_id=:id GROUP BY resultado_xml
                UNION ALL SELECT 'COMPROVANTE',resultado_comprovante,COUNT_BIG(*)
                FROM dbo.tb_reconciliacao_vedacit_item WHERE execucao_id=:id GROUP BY resultado_comprovante
                UNION ALL SELECT 'ACAO',acao,COUNT_BIG(*)
                FROM dbo.tb_reconciliacao_vedacit_item WHERE execucao_id=:id GROUP BY acao
                """,Map.of("id",id),(r,n)->new Resumo(r.getString(1),r.getString(2),r.getLong(3)));
    }
    public List<Map<String,Object>> execucoes() {
        return jdbc.queryForList("""
                SELECT TOP (30) e.*, (SELECT COUNT_BIG(*) FROM dbo.tb_reconciliacao_vedacit_item i
                    WHERE i.execucao_id=e.id) revisados FROM dbo.tb_reconciliacao_vedacit_execucao e ORDER BY id DESC
                """,Map.of());
    }
    public List<Map<String,Object>> itens(long id, long depois, int tamanho) {
        return jdbc.queryForList("""
                SELECT TOP (:tamanho) * FROM dbo.tb_reconciliacao_vedacit_item
                WHERE execucao_id=:id AND log_id>:depois ORDER BY log_id
                """,Map.of("id",id,"depois",depois,"tamanho",Math.max(1,Math.min(200,tamanho))));
    }
    private static Execucao execucao(ResultSet r) throws SQLException {
        return new Execucao(r.getLong("id"),r.getDate("dia_referencia").toLocalDate(),r.getLong("ultimo_log_id"),
                r.getLong("limite_log_id"),r.getString("status"),r.getString("bloqueio_sftp"),r.getString("bloqueio_consulta_xml"),
                r.getString("bloqueio_consulta_pod"),r.getString("bloqueio_origem_xml"),r.getString("bloqueio_envio_xml"),r.getString("bloqueio_envio_pod"));
    }
    private static LocalDateTime data(ResultSet r,String campo) throws SQLException {
        var t=r.getTimestamp(campo); return t==null?null:t.toLocalDateTime();
    }
}
