USE [SATELITE_TMS_AUDITORIA];
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Somente leitura. Chaves usadas em memoria pela verificacao dirigida; nao publicar a saida bruta.
SELECT id, chave_nfe, chave_cte, status_dados
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
  AND id IN (10561, 10562, 10563, 10564, 10565, 10566)
ORDER BY id;
GO
-- Inclui sucessos historicos arquivados na busca de evidencia, sem reativar nem modificar registros.
SELECT l.id, l.chave_nfe, COALESCE(NULLIF(l.canhoto_chave_cte_efetiva, ''), l.chave_cte) AS chave_cte,
       l.data_processamento_canhoto,
       (SELECT COUNT_BIG(*) FROM dbo.tb_log_integracao s
        WHERE s.sistema_destino = 'VEDACIT' AND s.chave_nfe = l.chave_nfe
          AND COALESCE(NULLIF(s.canhoto_chave_cte_efetiva, ''), s.chave_cte)
              = COALESCE(NULLIF(l.canhoto_chave_cte_efetiva, ''), l.chave_cte)
          AND s.status_canhoto IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
          AND s.data_processamento_canhoto IS NOT NULL) AS confirmacoes_locais
FROM dbo.tb_log_integracao l
WHERE l.sistema_destino = 'VEDACIT' AND (l.arquivado = 0 OR l.arquivado IS NULL)
  AND l.canhoto_classificacao_operacional = 'TIMEOUT_AMBIGUO'
ORDER BY CASE WHEN l.id IN (10009, 10056, 10583, 10608) THEN 0 ELSE 1 END, l.id;
GO
