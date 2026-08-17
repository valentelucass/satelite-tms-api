:ON ERROR EXIT

USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'canhoto_chave_cte_efetiva') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD canhoto_chave_cte_efetiva VARCHAR(44) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'canhoto_reconciliacao_tipo') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD canhoto_reconciliacao_tipo VARCHAR(50) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'canhoto_reconciliacao_motivo') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD canhoto_reconciliacao_motivo NVARCHAR(500) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_tb_log_integracao_vedacit_nfe_canhoto' AND object_id = OBJECT_ID(N'dbo.tb_log_integracao'))
    CREATE NONCLUSTERED INDEX IX_tb_log_integracao_vedacit_nfe_canhoto ON dbo.tb_log_integracao (sistema_destino, chave_nfe, status_canhoto) INCLUDE (chave_cte, canhoto_chave_cte_efetiva);
GO
