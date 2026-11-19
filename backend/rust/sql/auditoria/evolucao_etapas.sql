SELECT CAST(data_etapa AS DATE) AS data, etapa,
    SUM(CAST(CASE WHEN classe = 'SUCESSO' THEN 1 ELSE 0 END AS BIGINT)) AS sucessos,
    SUM(CAST(CASE WHEN situacao IN ('ERRO_DESTINO', 'ERRO_VALIDACAO') THEN 1 ELSE 0 END AS BIGINT)) AS falhas
FROM classificadas
WHERE data_etapa >= @inicio AND data_etapa < @fim
  AND (classe = 'SUCESSO' OR situacao IN ('ERRO_DESTINO', 'ERRO_VALIDACAO'))
GROUP BY CAST(data_etapa AS DATE), etapa
ORDER BY data, etapa
