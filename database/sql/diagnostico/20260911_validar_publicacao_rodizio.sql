USE [SATELITE_TMS_AUDITORIA];
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
SELECT DB_NAME() AS database_atual, SYSDATETIME() AS verificado_em;
GO
SELECT c.name AS coluna, TYPE_NAME(c.user_type_id) AS tipo, c.is_nullable
FROM sys.columns c
WHERE c.object_id = OBJECT_ID('dbo.tb_work_sftp_cliente_execucao')
  AND c.name IN ('xml_habilitado', 'xml_avaliados', 'xml_enviados', 'xml_ja_processados',
                 'xml_pendentes', 'xml_erros', 'erros_comprovante', 'motivo_falha')
ORDER BY c.column_id;
GO
SELECT COUNT_BIG(*) AS timeouts_ativos
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
  AND canhoto_classificacao_operacional = 'TIMEOUT_AMBIGUO';
GO
