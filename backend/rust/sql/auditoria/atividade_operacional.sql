WITH eventos AS (
  SELECT e.*, ROW_NUMBER() OVER (PARTITION BY sistema_destino, etapa, documento, tipo ORDER BY ocorrido_em,id) AS ordem
  FROM dbo.tb_integracao_etapa_evento e WHERE sistema_destino IN (SELECT value FROM STRING_SPLIT(@destinos, ','))
)
SELECT CAST(ocorrido_em AS DATE) AS data, etapa,
  SUM(CAST(CASE WHEN tipo='AVALIACAO' THEN 1 ELSE 0 END AS BIGINT)) AS avaliacoes,
  SUM(CAST(CASE WHEN tipo='ENVIO_INICIADO' THEN 1 ELSE 0 END AS BIGINT)) AS enviosIniciados,
  SUM(CAST(CASE WHEN tipo='ENVIO_INICIADO' AND ordem>1 THEN 1 ELSE 0 END AS BIGINT)) AS reenviosIniciados,
  SUM(CAST(CASE WHEN tipo='CONSULTA_ORIGEM' THEN 1 ELSE 0 END AS BIGINT)) AS consultasOrigem,
  SUM(CAST(CASE WHEN tipo='AVALIACAO' AND acao NOT IN ('CONFIRMADO','IGNORADO') THEN 1 ELSE 0 END AS BIGINT)) AS retencoes,
  SUM(CAST(CASE WHEN tipo='AVALIACAO' AND acao='CONFIRMADO' THEN 1 ELSE 0 END AS BIGINT)) AS confirmacoesAvaliadas
FROM eventos WHERE ocorrido_em>=@inicio AND ocorrido_em<@fim
GROUP BY CAST(ocorrido_em AS DATE), etapa ORDER BY data, etapa
