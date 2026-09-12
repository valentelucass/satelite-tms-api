USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Amostras documentais para consultas externas somente leitura; nenhuma alteracao de estado.
SELECT 'amostra' consulta, id, chave_nfe, chave_cte, status_dados, status_canhoto
FROM dbo.tb_log_integracao
WHERE sistema_destino='VEDACIT' AND id IN (8331,7579);
SELECT TOP (10) 'xml_retido' consulta, id, chave_nfe, chave_cte, status_dados,
       tentativas_dados, data_processamento_dados, mensagem_erro_dados
FROM dbo.tb_log_integracao
WHERE sistema_destino='VEDACIT' AND (arquivado=0 OR arquivado IS NULL)
  AND mensagem_erro_dados LIKE 'ORIGEM_XML_%'
ORDER BY id;
SELECT 'rejeitado' consulta, id, chave_nfe, chave_cte, canhoto_referencia
FROM dbo.tb_log_integracao
WHERE sistema_destino='VEDACIT' AND sftp_cliente='VEDACIT'
  AND (arquivado=0 OR arquivado IS NULL) AND chave_cte IS NULL AND chave_nfe IS NULL
  AND canhoto_classificacao_operacional='BLOQUEADO_ORIGEM';
