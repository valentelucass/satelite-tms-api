USE [SATELITE_TMS_AUDITORIA];
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
SELECT TOP (3) id, sftp_cliente, inicio_em, fim_em, atualizado_em, status_ciclo,
       selecionados, enviados, xml_enviados, xml_erros
FROM dbo.tb_work_sftp_cliente_execucao
WHERE sftp_cliente = 'VEDACIT'
ORDER BY inicio_em DESC, id DESC;
GO
SELECT MAX(data_processamento_dados) AS ultimo_xml_confirmado
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
  AND status_dados IN ('SUCESSO', 'ENVIADO', 'PROCESSADO');
GO
SELECT MAX(data_processamento_canhoto) AS ultimo_comprovante_confirmado
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
  AND status_canhoto IN ('SUCESSO', 'ENVIADO', 'PROCESSADO');
GO
SELECT name AS coluna, TYPE_NAME(user_type_id) AS tipo, is_nullable
FROM sys.columns
WHERE object_id = OBJECT_ID('dbo.tb_work_sftp_cliente_execucao')
  AND name IN ('fim_em', 'execucao_id', 'atualizado_em');
GO
