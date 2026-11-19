WITH base_etapas AS (
    SELECT
        CONCAT(l.sistema_destino, N' - ', etapa.entidade) AS entidade_tabela,
        UPPER(LTRIM(RTRIM(COALESCE(etapa.status_etapa, N'')))) AS status_normalizado,
        COALESCE(etapa.tentativas, 0) AS tentativas
    FROM dbo.tb_log_integracao l
    CROSS APPLY (VALUES
        (N'XML/Dados', COALESCE(NULLIF(LTRIM(RTRIM(l.status_dados)), N''), NULLIF(LTRIM(RTRIM(l.status)), N'')), l.tentativas_dados),
        (N'Canhoto', COALESCE(NULLIF(LTRIM(RTRIM(l.status_canhoto)), N''), NULLIF(LTRIM(RTRIM(l.status)), N'')), l.tentativas_canhoto)
    ) etapa(entidade, status_etapa, tentativas)
    WHERE l.sistema_destino IN (SELECT value FROM STRING_SPLIT(@destinos, ','))
      AND COALESCE(l.arquivado, 0) = 0
      AND NOT (
    l.sistema_destino = 'VEDACIT'
    AND l.sftp_cliente IS NOT NULL
    AND l.canhoto_classificacao_operacional IN ('BLOQUEADO_ORIGEM', 'BLOQUEADO_DESTINO')
)
      AND l.data_processamento >= @inicio
      AND l.data_processamento < @fim
      AND NULLIF(LTRIM(RTRIM(COALESCE(etapa.status_etapa, N''))), N'') IS NOT NULL
),
classificadas AS (
    SELECT
        entidade_tabela,
        CASE
            WHEN status_normalizado IN (N'SUCESSO', N'ENVIADO', N'PROCESSADO', N'NAO_APLICAVEL') THEN N'SUCESSO'
            WHEN status_normalizado = N'PENDENTE_FOTO'
              OR (status_normalizado = N'ERRO_DESTINO' AND tentativas >= 3) THEN N'QUARENTENA'
            ELSE N'ERRO'
        END AS classe_status
    FROM base_etapas
)
SELECT
    entidade_tabela AS entidadeTabela,
    COUNT_BIG(1) AS totalProcessado,
    SUM(CASE WHEN classe_status = N'SUCESSO' THEN 1 ELSE 0 END) AS totalSucesso,
    SUM(CASE WHEN classe_status = N'ERRO' THEN 1 ELSE 0 END) AS totalErro,
    SUM(CASE WHEN classe_status = N'QUARENTENA' THEN 1 ELSE 0 END) AS totalQuarentena
FROM classificadas
GROUP BY entidade_tabela
ORDER BY
    SUM(CASE WHEN classe_status IN (N'ERRO', N'QUARENTENA') THEN 1 ELSE 0 END) DESC,
    COUNT_BIG(1) DESC,
    entidade_tabela ASC
FOR JSON PATH, INCLUDE_NULL_VALUES
-- O limite já usado pelo resumo do painel evita esperas longas por paralelismo.
OPTION (MAXDOP 1)
