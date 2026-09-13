USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

-- Somente leitura: distingue documentos retidos de downloads efetivamente recusados.
SELECT 'resumo' AS consulta,
       COUNT(*) AS registros,
       COUNT(DISTINCT chave_cte) AS ctes_distintos,
       SUM(CASE WHEN mensagem_erro_dados = 'ORIGEM_XML_HTTP_401' THEN 1 ELSE 0 END) AS acesso_recusado,
       SUM(CASE WHEN mensagem_erro_dados = 'ORIGEM_XML_AUTENTICACAO_EM_ESPERA' THEN 1 ELSE 0 END) AS aguardando_acesso
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT'
  AND (arquivado = 0 OR arquivado IS NULL)
  AND status_dados = 'ERRO_DESTINO'
  AND data_processamento_dados >= '20260912'
  AND data_processamento_dados < '20260913'
  AND mensagem_erro_dados LIKE 'ORIGEM_XML_%';

SELECT 'documento' AS consulta, id,
       SUBSTRING(chave_cte, 26, 9) AS numero_cte,
       data_processamento_dados, tentativas_dados, mensagem_erro_dados
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT'
  AND (arquivado = 0 OR arquivado IS NULL)
  AND status_dados = 'ERRO_DESTINO'
  AND data_processamento_dados >= '20260912'
  AND data_processamento_dados < '20260913'
  AND mensagem_erro_dados LIKE 'ORIGEM_XML_%'
ORDER BY data_processamento_dados, id;
