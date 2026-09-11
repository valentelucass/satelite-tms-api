:ON ERROR EXIT
USE [$(DatabaseName)];
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
IF LOWER(DB_NAME()) <> N'satelite_tms_auditoria'
    THROW 50001, 'Database fora do escopo autorizado.', 1;
GO
IF EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.tb_work_sftp_cliente_execucao') AND name = 'fim_em' AND is_nullable = 0)
    ALTER TABLE dbo.tb_work_sftp_cliente_execucao ALTER COLUMN fim_em DATETIME2(3) NULL;
IF COL_LENGTH('dbo.tb_work_sftp_cliente_execucao', 'execucao_id') IS NULL
    ALTER TABLE dbo.tb_work_sftp_cliente_execucao ADD execucao_id UNIQUEIDENTIFIER NULL;
IF COL_LENGTH('dbo.tb_work_sftp_cliente_execucao', 'atualizado_em') IS NULL
    ALTER TABLE dbo.tb_work_sftp_cliente_execucao ADD atualizado_em DATETIME2(3) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('dbo.tb_work_sftp_cliente_execucao') AND name = 'UX_work_sftp_execucao_id')
    CREATE UNIQUE INDEX UX_work_sftp_execucao_id ON dbo.tb_work_sftp_cliente_execucao(execucao_id) WHERE execucao_id IS NOT NULL;
GO
