SELECT CAST(data_etapa AS DATE) AS data, etapa, SUM(CAST(1 AS BIGINT)) AS sucessos FROM operacionais
WHERE classe='SUCESSO' AND data_etapa>=@inicio AND data_etapa<@fim
GROUP BY CAST(data_etapa AS DATE), etapa ORDER BY data, etapa
