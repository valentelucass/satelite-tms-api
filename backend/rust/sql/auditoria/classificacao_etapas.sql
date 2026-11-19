WITH etapas AS (
    SELECT id, sistema_destino, occurrence_id, chave_cte, chave_nfe,
           data_processamento, 'DADOS' AS etapa,
           status_dados AS status_etapa, data_processamento_dados AS data_etapa,
           CAST(NULL AS VARCHAR(40)) AS bloqueio
    FROM dbo.tb_log_integracao
    WHERE (arquivado = 0 OR arquivado IS NULL) AND sistema_destino IN (SELECT value FROM STRING_SPLIT(@destinos, ','))
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
    WHERE (l.arquivado = 0 OR l.arquivado IS NULL) AND l.sistema_destino IN (SELECT value FROM STRING_SPLIT(@destinos, ','))
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
