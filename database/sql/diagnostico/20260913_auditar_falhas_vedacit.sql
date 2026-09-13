USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

-- Somente leitura. O estado documental guarda o ultimo resultado; ciclos/logs
-- precisam ser consultados para contar avaliacoes repetidas do mesmo CT-e.
SELECT 'xml_resumo' AS consulta, COUNT(*) AS registros,
       COUNT(DISTINCT chave_cte) AS ctes_distintos,
       SUM(COALESCE(tentativas_dados, 0)) AS avaliacoes_acumuladas,
       MIN(data_processamento_dados) AS primeira_ultima_avaliacao,
       MAX(data_processamento_dados) AS ultima_avaliacao
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT'
  AND (arquivado = 0 OR arquivado IS NULL)
  AND status_dados = 'ERRO_DESTINO'
  AND mensagem_erro_dados LIKE 'ORIGEM_XML_%';

SELECT 'xml_causa_atual' AS consulta, mensagem_erro_dados AS causa,
       COUNT(*) AS registros
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT'
  AND (arquivado = 0 OR arquivado IS NULL)
  AND status_dados = 'ERRO_DESTINO'
  AND mensagem_erro_dados LIKE 'ORIGEM_XML_%'
GROUP BY mensagem_erro_dados;

SELECT 'xml_documento' AS consulta, id,
       SUBSTRING(chave_cte, 26, 9) AS numero_cte,
       data_processamento_dados, tentativas_dados, mensagem_erro_dados AS causa
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT'
  AND (arquivado = 0 OR arquivado IS NULL)
  AND status_dados = 'ERRO_DESTINO'
  AND mensagem_erro_dados LIKE 'ORIGEM_XML_%'
ORDER BY id;

SELECT 'ciclo' AS consulta, id, inicio_em, fim_em, status_ciclo, conexao,
       xml_avaliados, xml_enviados, xml_ja_processados, xml_pendentes, xml_erros,
       selecionados, enviados, pendentes, erros_comprovante, saldo, bloqueios,
       timeouts_ambiguos
FROM dbo.tb_work_sftp_cliente_execucao
WHERE sftp_cliente = 'VEDACIT'
  AND inicio_em >= '2026-09-12T19:57:00'
  AND inicio_em < '2026-09-13T01:05:00'
ORDER BY inicio_em;

SELECT 'pod_classificacao' AS consulta, canhoto_classificacao_operacional AS classificacao,
       status_canhoto, COUNT(*) AS registros
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT'
  AND (arquivado = 0 OR arquivado IS NULL)
GROUP BY canhoto_classificacao_operacional, status_canhoto;

SELECT 'pod_pendente' AS consulta, id,
       SUBSTRING(chave_nfe, 26, 9) AS numero_nfe,
       SUBSTRING(chave_cte, 26, 9) AS numero_cte,
       status_dados, status_canhoto, canhoto_classificacao_operacional,
       tentativas_canhoto, data_processamento_canhoto
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT'
  AND (arquivado = 0 OR arquivado IS NULL)
  AND canhoto_classificacao_operacional = 'PENDENTE_ENVIO';
