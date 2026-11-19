SELECT sistema_destino AS sistemaDestino, etapa,
  SUM(CAST(CASE WHEN classe='SUCESSO' AND data_etapa>=@inicio AND data_etapa<@fim THEN 1 ELSE 0 END AS BIGINT)) AS sucessosPeriodo,
  SUM(CAST(CASE WHEN acao='AGUARDANDO_PROCESSAMENTO' THEN 1 ELSE 0 END AS BIGINT)) AS aguardandoProcessamento,
  SUM(CAST(CASE WHEN acao='NOVA_TENTATIVA' THEN 1 ELSE 0 END AS BIGINT)) AS novaTentativa,
  SUM(CAST(CASE WHEN acao='AGUARDANDO_ORIGEM' THEN 1 ELSE 0 END AS BIGINT)) AS aguardandoOrigem,
  SUM(CAST(CASE WHEN acao='AGUARDANDO_CONFIRMACAO' THEN 1 ELSE 0 END AS BIGINT)) AS aguardandoConfirmacao,
  SUM(CAST(CASE WHEN acao='INTERVENCAO' THEN 1 ELSE 0 END AS BIGINT)) AS intervencao,
  SUM(CAST(CASE WHEN acao='A_CONFERIR' THEN 1 ELSE 0 END AS BIGINT)) AS semConfirmacaoDatada,
  SUM(CAST(CASE WHEN classe='CONFIRMADO_SEM_DATA' THEN 1 ELSE 0 END AS BIGINT)) AS confirmadosSemDataConfiavel
FROM operacionais GROUP BY sistema_destino, etapa ORDER BY sistema_destino, etapa
