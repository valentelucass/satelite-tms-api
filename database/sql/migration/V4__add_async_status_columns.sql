:ON ERROR EXIT

USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'status_dados') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD status_dados VARCHAR(50) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'status_canhoto') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD status_canhoto VARCHAR(50) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'mensagem_erro_dados') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD mensagem_erro_dados NVARCHAR(MAX) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'mensagem_erro_canhoto') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD mensagem_erro_canhoto NVARCHAR(MAX) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'data_processamento_dados') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD data_processamento_dados DATETIME2(3) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'data_processamento_canhoto') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD data_processamento_canhoto DATETIME2(3) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'tentativas_dados') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD tentativas_dados INT NOT NULL
        CONSTRAINT DF_tb_log_integracao_tentativas_dados DEFAULT 0;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'tentativas_canhoto') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD tentativas_canhoto INT NOT NULL
        CONSTRAINT DF_tb_log_integracao_tentativas_canhoto DEFAULT 0;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = N'IX_tb_log_integracao_destino_status_canhoto'
      AND object_id = OBJECT_ID(N'dbo.tb_log_integracao')
)
BEGIN
    CREATE INDEX IX_tb_log_integracao_destino_status_canhoto
    ON dbo.tb_log_integracao (sistema_destino, status_canhoto, data_processamento ASC, id ASC)
    INCLUDE (occurrence_id, chave_nfe, freight_id, status_dados, status);
END;
GO
