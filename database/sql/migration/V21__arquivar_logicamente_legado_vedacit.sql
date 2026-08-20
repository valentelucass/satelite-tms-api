USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'arquivado') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD arquivado BIT NOT NULL
        CONSTRAINT DF_tb_log_integracao_arquivado DEFAULT 0;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'arquivado_em') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD arquivado_em DATETIME2(3) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'arquivado_motivo') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD arquivado_motivo VARCHAR(100) NULL;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE object_id = OBJECT_ID(N'dbo.tb_log_integracao')
      AND name = N'IX_tb_log_integracao_arquivado_destino'
)
BEGIN
    CREATE INDEX IX_tb_log_integracao_arquivado_destino
        ON dbo.tb_log_integracao (arquivado, sistema_destino, data_processamento DESC, id DESC);
END;
GO

/* Preserva a auditoria e retira exclusivamente o legado Vedacit do uso operacional. */
UPDATE dbo.tb_log_integracao
SET arquivado = 1,
    arquivado_em = COALESCE(arquivado_em, SYSDATETIME()),
    arquivado_motivo = COALESCE(arquivado_motivo, 'LEGADO_SEM_CLIENTE_SFTP')
WHERE sistema_destino = 'VEDACIT'
  AND sftp_cliente IS NULL
  AND COALESCE(arquivado, 0) = 0;
GO
