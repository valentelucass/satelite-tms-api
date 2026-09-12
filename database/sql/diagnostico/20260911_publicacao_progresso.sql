USE [SATELITE_TMS_AUDITORIA];
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
SET NOCOUNT ON;
IF DB_NAME() <> N'SATELITE_TMS_AUDITORIA'
    THROW 51000, 'Database fora do escopo.', 1;
SELECT SYSDATETIMEOFFSET() AS consultado_em, c.name AS coluna, c.is_nullable
  FROM sys.columns c
 WHERE c.object_id = OBJECT_ID(N'dbo.tb_work_sftp_cliente_execucao')
   AND c.name IN (N'execucao_id', N'atualizado_em', N'fim_em');
