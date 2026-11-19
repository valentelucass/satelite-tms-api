SELECT
    CAST(l.data_processamento AS DATE) AS data,
    COUNT(l.id) AS total,
    SUM(CASE
        WHEN (
            COALESCE(UPPER(NULLIF(TRIM(l.status), '')), '') IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
            OR COALESCE(UPPER(NULLIF(TRIM(l.status_dados), '')), '') IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
            OR COALESCE(UPPER(NULLIF(TRIM(l.status_canhoto), '')), '') IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
        )
        THEN 1 ELSE 0 END) AS sucessos,
    SUM(CASE
        WHEN (
            COALESCE(UPPER(NULLIF(TRIM(l.status), '')), '') IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
            OR COALESCE(UPPER(NULLIF(TRIM(l.status_dados), '')), '') IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
            OR COALESCE(UPPER(NULLIF(TRIM(l.status_canhoto), '')), '') IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
        )
        THEN 0 ELSE 1 END) AS erros
FROM dbo.tb_log_integracao l
WHERE l.sistema_destino IN (SELECT value FROM STRING_SPLIT(@destinos, ','))
  AND COALESCE(l.arquivado, 0) = 0
  AND NOT (
        l.sistema_destino = 'VEDACIT'
        AND l.sftp_cliente IS NOT NULL
        AND l.canhoto_classificacao_operacional IN ('BLOQUEADO_ORIGEM', 'BLOQUEADO_DESTINO')
  )
  AND l.data_processamento >= @inicio
  AND l.data_processamento < @fim
GROUP BY CAST(l.data_processamento AS DATE)
ORDER BY data ASC
