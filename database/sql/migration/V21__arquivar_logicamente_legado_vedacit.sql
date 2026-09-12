USE [$(DatabaseName)];
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

/* O arquivamento de legado exige uma ação com manifesto delimitado.
   O atualizador reaplica este arquivo: sftp_cliente NULL também identifica XMLs
   recentes. Não repetir o arquivamento de dados pelo provisionamento do schema.
   Os logs já arquivados são preservados. */
