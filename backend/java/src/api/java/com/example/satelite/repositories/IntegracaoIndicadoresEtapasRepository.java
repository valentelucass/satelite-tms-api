package com.example.satelite.repositories;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.satelite.dto.auditoria.IndicadoresEtapasDTO;

/** Indicadores de estado por etapa. Não reconstitui tentativas já sobrescritas no log. */
@Repository
public class IntegracaoIndicadoresEtapasRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public IntegracaoIndicadoresEtapasRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Sucesso com data tem precedência sobre cópias pendentes do mesmo documento.
    // A primeira confirmação datada impede que reconciliações posteriores virem novos envios.
    static final String BASE = """
            WITH etapas AS (
                SELECT id, sistema_destino, occurrence_id, chave_cte, chave_nfe,
                       data_processamento, 'DADOS' AS etapa,
                       status_dados AS status_etapa, data_processamento_dados AS data_etapa,
                       CAST(NULL AS VARCHAR(40)) AS bloqueio
                FROM dbo.tb_log_integracao
                WHERE (arquivado = 0 OR arquivado IS NULL) AND sistema_destino IN (:destinos)
                UNION ALL
                SELECT l.id, l.sistema_destino, l.occurrence_id,
                       COALESCE(NULLIF(l.canhoto_chave_cte_efetiva, ''), l.chave_cte), l.chave_nfe,
                       l.data_processamento, 'COMPROVANTE',
                       CASE WHEN c.chave_nfe IS NOT NULL THEN 'SUCESSO' ELSE l.status_canhoto END,
                       CASE WHEN c.chave_nfe IS NOT NULL THEN c.primeira_confirmacao_em ELSE l.data_processamento_canhoto END,
                       CASE WHEN c.chave_nfe IS NOT NULL THEN NULL ELSE l.canhoto_classificacao_operacional END
                FROM dbo.tb_log_integracao l LEFT JOIN dbo.tb_confirmacao_comprovante c
                    ON l.sistema_destino = 'VEDACIT' AND c.chave_nfe = l.chave_nfe
                    AND c.chave_cte = COALESCE(NULLIF(l.canhoto_chave_cte_efetiva, ''), l.chave_cte)
                WHERE (l.arquivado = 0 OR l.arquivado IS NULL) AND l.sistema_destino IN (:destinos)
            ), normalizadas AS (
                SELECT *, UPPER(TRIM(COALESCE(status_etapa, ''))) AS situacao,
                    CASE
                        WHEN sistema_destino = 'VEDACIT' AND etapa = 'DADOS' AND NULLIF(chave_cte, '') IS NOT NULL
                            THEN CONCAT('CTE:', chave_cte)
                        WHEN sistema_destino = 'VEDACIT' AND etapa = 'COMPROVANTE'
                             AND NULLIF(chave_nfe, '') IS NOT NULL AND NULLIF(chave_cte, '') IS NOT NULL
                            THEN CONCAT('POD:', chave_nfe, ':', chave_cte)
                        WHEN sistema_destino <> 'VEDACIT' AND occurrence_id IS NOT NULL
                            THEN CONCAT('OCORRENCIA:', CAST(occurrence_id AS VARCHAR(30)))
                        ELSE CONCAT('LOG:', CAST(id AS VARCHAR(30)))
                    END AS documento
                FROM etapas
            ), ordenadas AS (
                SELECT *, ROW_NUMBER() OVER (
                    PARTITION BY sistema_destino, etapa, documento
                    ORDER BY CASE WHEN situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
                                       AND data_etapa IS NOT NULL THEN 0
                                  WHEN situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') THEN 1 ELSE 2 END,
                             CASE WHEN situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
                                       AND data_etapa IS NOT NULL THEN data_etapa END ASC,
                             data_processamento DESC, id DESC
                ) AS ordem
                FROM normalizadas
            ), classificadas AS (
                SELECT *, CASE
                    WHEN situacao IN ('NAO_APLICAVEL', 'IGNORADO') THEN 'IGNORADO'
                    WHEN situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') AND data_etapa IS NOT NULL THEN 'SUCESSO'
                    WHEN situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') AND etapa = 'COMPROVANTE' THEN 'CONFIRMADO_SEM_DATA'
                    WHEN situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') THEN 'SEM_CONFIRMACAO'
                    WHEN (etapa = 'DADOS' AND situacao = 'PENDENTE_ORIGEM')
                         OR bloqueio IN ('BLOQUEADO_ORIGEM', 'BLOQUEADO_DESTINO', 'TIMEOUT_AMBIGUO') THEN 'BLOQUEADO'
                    WHEN situacao IN ('PENDENTE', 'PENDENTE_FOTO', 'PENDENTE_ORIGEM', 'PENDENTE_ENVIO',
                                      'PENDENTE_TECNICO', 'EM_PROCESSAMENTO', 'ERRO_DESTINO', 'ERRO_VALIDACAO') THEN 'PENDENTE'
                    ELSE 'SEM_CONFIRMACAO'
                END AS classe
                FROM ordenadas WHERE ordem = 1
            )
            """;

    static final String RESUMO = BASE + """
            SELECT sistema_destino, etapa,
                SUM(CAST(CASE WHEN classe = 'SUCESSO' AND data_etapa >= :inicio AND data_etapa < :fim
                    THEN 1 ELSE 0 END AS BIGINT)) AS sucessos,
                SUM(CAST(CASE WHEN situacao IN ('ERRO_DESTINO', 'ERRO_VALIDACAO')
                    AND data_etapa >= :inicio AND data_etapa < :fim THEN 1 ELSE 0 END AS BIGINT)) AS falhas,
                SUM(CAST(CASE WHEN classe = 'PENDENTE' THEN 1 ELSE 0 END AS BIGINT)) AS pendentes,
                SUM(CAST(CASE WHEN classe = 'BLOQUEADO' THEN 1 ELSE 0 END AS BIGINT)) AS bloqueados,
                SUM(CAST(CASE WHEN classe = 'SEM_CONFIRMACAO' THEN 1 ELSE 0 END AS BIGINT)) AS sem_confirmacao,
                SUM(CAST(CASE WHEN classe = 'CONFIRMADO_SEM_DATA' THEN 1 ELSE 0 END AS BIGINT)) AS confirmados_sem_data
            FROM classificadas
            GROUP BY sistema_destino, etapa
            ORDER BY sistema_destino, etapa
            """;

    static final String EVOLUCAO = BASE + """
            SELECT CAST(data_etapa AS DATE) AS dia, etapa,
                SUM(CAST(CASE WHEN classe = 'SUCESSO' THEN 1 ELSE 0 END AS BIGINT)) AS sucessos,
                SUM(CAST(CASE WHEN situacao IN ('ERRO_DESTINO', 'ERRO_VALIDACAO') THEN 1 ELSE 0 END AS BIGINT)) AS falhas
            FROM classificadas
            WHERE data_etapa >= :inicio AND data_etapa < :fim
              AND (classe = 'SUCESSO' OR situacao IN ('ERRO_DESTINO', 'ERRO_VALIDACAO'))
            GROUP BY CAST(data_etapa AS DATE), etapa
            ORDER BY dia, etapa
            """;

    @Transactional(readOnly = true)
    public IndicadoresEtapasDTO consultar(LocalDate inicio, LocalDate fim, List<String> destinos) {
        var params = new MapSqlParameterSource("destinos", destinos)
                .addValue("inicio", inicio.atStartOfDay()).addValue("fim", fim.plusDays(1).atStartOfDay());
        var etapas = jdbc.query(RESUMO, params, (rs, n) -> new IndicadoresEtapasDTO.Etapa(
                rs.getString("sistema_destino"), rs.getString("etapa"), rs.getLong("sucessos"),
                rs.getLong("falhas"), rs.getLong("pendentes"), rs.getLong("bloqueados"), rs.getLong("sem_confirmacao"),
                rs.getLong("confirmados_sem_data")));
        var dias = jdbc.query(EVOLUCAO, params, (rs, n) -> new IndicadoresEtapasDTO.Dia(
                rs.getDate("dia").toLocalDate(), rs.getString("etapa"), rs.getLong("sucessos"), rs.getLong("falhas")));
        return new IndicadoresEtapasDTO(2, inicio, fim, etapas, dias);
    }
}
