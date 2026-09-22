package com.example.satelite.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.example.satelite.dto.auditoria.IndicadoresOperacionaisDTO;
import com.example.satelite.dto.auditoria.PendenciasEtapasDTO;

@Repository
public class IntegracaoOperacionalRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public IntegracaoOperacionalRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // A deduplicação e as primeiras confirmações são exatamente as do contrato v2.
    static final String BASE = IntegracaoIndicadoresEtapasRepository.BASE + """
        , motivos AS (
          SELECT c.*, UPPER(COALESCE(CASE WHEN c.etapa='DADOS' THEN l.mensagem_erro_dados ELSE l.mensagem_erro_canhoto END,'')) AS mensagem,
            CASE WHEN c.etapa='DADOS' THEN l.dados_pendente_desde ELSE l.canhoto_pendente_desde END AS pendente_desde,
            CASE WHEN c.etapa='DADOS' THEN l.dados_proxima_avaliacao_em ELSE l.canhoto_proxima_avaliacao_em END AS proxima_avaliacao
          FROM classificadas c JOIN dbo.tb_log_integracao l ON l.id=c.id
        ), operacionais AS (
          SELECT *, CASE
            WHEN classe IN ('SUCESSO','CONFIRMADO_SEM_DATA') THEN 'CONFIRMADO'
            WHEN classe='IGNORADO' THEN 'IGNORADO'
            WHEN classe='SEM_CONFIRMACAO' AND situacao IN ('SUCESSO','ENVIADO','PROCESSADO') THEN 'A_CONFERIR'
            WHEN mensagem LIKE 'SOAP_ANTERIOR_EM_ANDAMENTO%' THEN 'NOVA_TENTATIVA'
            WHEN bloqueio='TIMEOUT_AMBIGUO' OR mensagem LIKE '%SEM CONFIRMA%' OR mensagem LIKE '%SEM_CONFIRMA%'
              OR mensagem LIKE '%RESULTADO_DESCONHECIDO%' OR mensagem LIKE '%ENVIO_INICIADO_SEM_RESULTADO%'
              OR mensagem LIKE '%READ TIMED OUT%' OR mensagem LIKE '%SOCKETTIMEOUT%' OR mensagem LIKE '%TIMEOUT TOTAL DA VEDACIT%'
              OR mensagem LIKE '%INTERROMPIDO DURANTE ENVIO%' THEN 'AGUARDANDO_CONFIRMACAO'
            WHEN bloqueio='BLOQUEADO_DESTINO' OR situacao='ERRO_VALIDACAO' OR mensagem LIKE '%CORRELACAO_INVALIDA%'
              OR mensagem LIKE '%CHAVE_INVALIDA%' OR mensagem LIKE '%VEDACIT RECUSOU%' THEN 'INTERVENCAO'
            WHEN bloqueio='BLOQUEADO_ORIGEM' OR situacao IN ('PENDENTE_ORIGEM','PENDENTE_FOTO')
              OR mensagem IN ('ORIGEM_XML_HTTP_401','ORIGEM_XML_HTTP_403','ORIGEM_XML_AUTENTICACAO_EM_ESPERA','ORIGEM_XML_AUSENTE')
              OR mensagem LIKE 'DATA_ENTREGA_%' THEN 'AGUARDANDO_ORIGEM'
            WHEN bloqueio='PENDENTE_TECNICO' OR situacao IN ('PENDENTE_TECNICO','ERRO_DESTINO') THEN 'NOVA_TENTATIVA'
            WHEN situacao IN ('RECEBIDO','PENDENTE','PENDENTE_ENVIO','EM_PROCESSAMENTO') THEN 'AGUARDANDO_PROCESSAMENTO'
            ELSE 'A_CONFERIR' END AS acao
          FROM motivos
        )
        """;

    static final String RESUMO = BASE + """
        SELECT sistema_destino, etapa,
          SUM(CAST(CASE WHEN classe='SUCESSO' AND data_etapa>=:inicio AND data_etapa<:fim THEN 1 ELSE 0 END AS BIGINT)) AS sucessos,
          SUM(CAST(CASE WHEN acao='AGUARDANDO_PROCESSAMENTO' THEN 1 ELSE 0 END AS BIGINT)) AS processamento,
          SUM(CAST(CASE WHEN acao='NOVA_TENTATIVA' THEN 1 ELSE 0 END AS BIGINT)) AS nova_tentativa,
          SUM(CAST(CASE WHEN acao='AGUARDANDO_ORIGEM' THEN 1 ELSE 0 END AS BIGINT)) AS origem,
          SUM(CAST(CASE WHEN acao='AGUARDANDO_CONFIRMACAO' THEN 1 ELSE 0 END AS BIGINT)) AS confirmacao,
          SUM(CAST(CASE WHEN acao='INTERVENCAO' THEN 1 ELSE 0 END AS BIGINT)) AS intervencao,
          SUM(CAST(CASE WHEN acao='A_CONFERIR' THEN 1 ELSE 0 END AS BIGINT)) AS conferir,
          SUM(CAST(CASE WHEN classe='CONFIRMADO_SEM_DATA' THEN 1 ELSE 0 END AS BIGINT)) AS confirmado_sem_data
        FROM operacionais GROUP BY sistema_destino, etapa ORDER BY sistema_destino, etapa
        """;

    static final String CONFIRMACOES = BASE + """
        SELECT CAST(data_etapa AS DATE) AS dia, etapa, SUM(CAST(1 AS BIGINT)) AS sucessos FROM operacionais
        WHERE classe='SUCESSO' AND data_etapa>=:inicio AND data_etapa<:fim
        GROUP BY CAST(data_etapa AS DATE), etapa ORDER BY dia, etapa
        """;

    static final String ATIVIDADE = """
        WITH eventos AS (
          SELECT e.*, ROW_NUMBER() OVER (PARTITION BY sistema_destino, etapa, documento, tipo ORDER BY ocorrido_em,id) AS ordem
          FROM dbo.tb_integracao_etapa_evento e WHERE sistema_destino IN (:destinos)
        )
        SELECT CAST(ocorrido_em AS DATE) AS dia, etapa,
          SUM(CAST(CASE WHEN tipo='AVALIACAO' THEN 1 ELSE 0 END AS BIGINT)) AS avaliacoes,
          SUM(CAST(CASE WHEN tipo='ENVIO_INICIADO' THEN 1 ELSE 0 END AS BIGINT)) AS envios,
          SUM(CAST(CASE WHEN tipo='ENVIO_INICIADO' AND ordem>1 THEN 1 ELSE 0 END AS BIGINT)) AS reenvios,
          SUM(CAST(CASE WHEN tipo='CONSULTA_ORIGEM' THEN 1 ELSE 0 END AS BIGINT)) AS consultas,
          SUM(CAST(CASE WHEN tipo='AVALIACAO' AND acao NOT IN ('CONFIRMADO','IGNORADO') THEN 1 ELSE 0 END AS BIGINT)) AS retencoes,
          SUM(CAST(CASE WHEN tipo='AVALIACAO' AND acao='CONFIRMADO' THEN 1 ELSE 0 END AS BIGINT)) AS confirmacoes
        FROM eventos WHERE ocorrido_em>=:inicio AND ocorrido_em<:fim
        GROUP BY CAST(ocorrido_em AS DATE), etapa ORDER BY dia, etapa
        """;

    @Transactional(readOnly=true)
    public IndicadoresOperacionaisDTO consultar(LocalDate inicio, LocalDate fim, List<String> destinos) {
        var p = new MapSqlParameterSource("destinos", destinos).addValue("inicio", inicio.atStartOfDay()).addValue("fim", fim.plusDays(1).atStartOfDay());
        var etapas = jdbc.query(RESUMO, p, (r,n) -> new IndicadoresOperacionaisDTO.Etapa(r.getString("sistema_destino"), r.getString("etapa"),
                r.getLong("sucessos"), r.getLong("processamento"), r.getLong("nova_tentativa"), r.getLong("origem"),
                r.getLong("confirmacao"), r.getLong("intervencao"), r.getLong("conferir"), r.getLong("confirmado_sem_data")));
        var dias = jdbc.query(CONFIRMACOES, p, (r,n) -> new IndicadoresOperacionaisDTO.Dia(r.getDate("dia").toLocalDate(), r.getString("etapa"), r.getLong("sucessos")));
        var atividades = jdbc.query(ATIVIDADE, p, (r,n) -> new IndicadoresOperacionaisDTO.Atividade(r.getDate("dia").toLocalDate(), r.getString("etapa"),
                r.getLong("avaliacoes"), r.getLong("envios"), r.getLong("reenvios"), r.getLong("consultas"), r.getLong("retencoes"), r.getLong("confirmacoes")));
        var desde = jdbc.queryForObject("SELECT MIN(ocorrido_em) FROM dbo.tb_integracao_etapa_evento WHERE sistema_destino IN (:destinos)", p,
                (r,n) -> data(r, 1));
        return new IndicadoresOperacionaisDTO(3, inicio, fim, etapas, dias, atividades, desde, "VEDACIT");
    }

    @Transactional(readOnly=true)
    public PendenciasEtapasDTO pendencias(List<String> destinos, String etapa, String acao, int pagina, int tamanho) {
        var p = new MapSqlParameterSource("destinos", destinos).addValue("etapa", etapa).addValue("acao", acao)
                .addValue("offset", (long)pagina*tamanho).addValue("tamanho", tamanho);
        String filtro = " WHERE acao NOT IN ('CONFIRMADO','IGNORADO') AND etapa=:etapa AND acao=:acao ";
        long total = jdbc.queryForObject(BASE + " SELECT COALESCE(SUM(CAST(1 AS BIGINT)),0) FROM operacionais " + filtro, p, Long.class);
        var itens = jdbc.query(BASE + """
                SELECT id, sistema_destino, etapa, acao, pendente_desde, proxima_avaliacao,
                  COALESCE((SELECT MAX(e.ocorrido_em) FROM dbo.tb_integracao_etapa_evento e
                    WHERE e.log_id=o.id AND e.etapa=o.etapa AND e.tipo='AVALIACAO'),data_etapa) AS ultima_avaliacao,
                  CASE WHEN etapa='DADOS' AND LEN(chave_cte)=44 THEN CONCAT('CT-e ', SUBSTRING(chave_cte,26,9))
                       WHEN LEN(chave_nfe)=44 THEN CONCAT('NF-e ', SUBSTRING(chave_nfe,26,9)) ELSE 'Documento sem identificação' END AS rotulo,
                  CASE WHEN mensagem IN ('ORIGEM_XML_HTTP_401','ORIGEM_XML_HTTP_403') THEN 'ACESSO_ORIGEM_RECUSADO'
                       WHEN mensagem='ORIGEM_XML_AUTENTICACAO_EM_ESPERA' THEN 'ESPERA_ACESSO_ORIGEM'
                       WHEN mensagem LIKE 'DATA_ENTREGA_%' THEN 'METADADO_ENTREGA'
                       WHEN mensagem='ORIGEM_XML_AUSENTE' THEN 'DOCUMENTO_AUSENTE' ELSE acao END AS motivo
                FROM operacionais o
                """ + filtro + " ORDER BY pendente_desde, data_etapa, id OFFSET :offset ROWS FETCH NEXT :tamanho ROWS ONLY", p,
                (r,n) -> new PendenciasEtapasDTO.Item(r.getLong("id"), r.getString("sistema_destino"), r.getString("etapa"),
                        r.getString("rotulo"), r.getString("acao"), r.getString("motivo"), data(r,"pendente_desde"), data(r,"ultima_avaliacao"), data(r,"proxima_avaliacao")));
        return new PendenciasEtapasDTO(itens,total,pagina,tamanho);
    }
    private static LocalDateTime data(ResultSet r, String nome) throws SQLException { var t=r.getTimestamp(nome); return t==null?null:t.toLocalDateTime(); }
    private static LocalDateTime data(ResultSet r, int indice) throws SQLException { var t=r.getTimestamp(indice); return t==null?null:t.toLocalDateTime(); }
}
