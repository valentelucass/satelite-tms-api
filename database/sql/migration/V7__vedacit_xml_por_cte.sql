USE SATELITE_TMS_AUDITORIA;
GO
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'chave_cte') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD chave_cte VARCHAR(44) NULL;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = N'IX_tb_log_integracao_destino_chave_cte'
      AND object_id = OBJECT_ID(N'dbo.tb_log_integracao')
)
BEGIN
    CREATE NONCLUSTERED INDEX IX_tb_log_integracao_destino_chave_cte
    ON dbo.tb_log_integracao (sistema_destino, chave_cte, data_processamento DESC, id DESC)
    INCLUDE (occurrence_id, chave_nfe, freight_id, status, status_dados, status_canhoto);
END;
GO
