SELECT sistema_destino AS sistemaDestino, etapa,
    SUM(CAST(CASE WHEN classe = 'SUCESSO' AND data_etapa >= @inicio AND data_etapa < @fim
        THEN 1 ELSE 0 END AS BIGINT)) AS sucessosPeriodo,
    SUM(CAST(CASE WHEN situacao IN ('ERRO_DESTINO', 'ERRO_VALIDACAO')
        AND data_etapa >= @inicio AND data_etapa < @fim THEN 1 ELSE 0 END AS BIGINT)) AS falhasPeriodo,
    SUM(CAST(CASE WHEN classe = 'PENDENTE' THEN 1 ELSE 0 END AS BIGINT)) AS pendentesAtuais,
    SUM(CAST(CASE WHEN classe = 'BLOQUEADO' THEN 1 ELSE 0 END AS BIGINT)) AS bloqueadosAtuais,
    SUM(CAST(CASE WHEN classe = 'SEM_CONFIRMACAO' THEN 1 ELSE 0 END AS BIGINT)) AS semConfirmacaoDatada,
    SUM(CAST(CASE WHEN classe = 'CONFIRMADO_SEM_DATA' THEN 1 ELSE 0 END AS BIGINT)) AS confirmadosSemDataConfiavel
FROM classificadas
GROUP BY sistema_destino, etapa
ORDER BY sistema_destino, etapa
