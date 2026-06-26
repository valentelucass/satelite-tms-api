:ON ERROR EXIT

USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF OBJECT_ID(N'dbo.tb_log_integracao', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.tb_log_integracao (
        id BIGINT IDENTITY(1,1) NOT NULL,
        occurrence_id BIGINT NULL,
        chave_nfe VARCHAR(44) NULL,
        freight_id BIGINT NULL,
        cursor_next_id BIGINT NULL,
        status VARCHAR(50) NOT NULL,
        sistema_destino VARCHAR(20) NULL,
        request_payload NVARCHAR(MAX) NULL,
        response_payload NVARCHAR(MAX) NULL,
        erro NVARCHAR(MAX) NULL,
        status_dados VARCHAR(50) NULL,
        status_canhoto VARCHAR(50) NULL,
        mensagem_erro_dados NVARCHAR(MAX) NULL,
        mensagem_erro_canhoto NVARCHAR(MAX) NULL,
        data_processamento_dados DATETIME2(3) NULL,
        data_processamento_canhoto DATETIME2(3) NULL,
        tentativas_dados INT NOT NULL
            CONSTRAINT DF_tb_log_integracao_tentativas_dados DEFAULT 0,
        tentativas_canhoto INT NOT NULL
            CONSTRAINT DF_tb_log_integracao_tentativas_canhoto DEFAULT 0,
        canhoto_referencia NVARCHAR(2048) NULL,
        canhoto_mime_type VARCHAR(100) NULL,
        canhoto_origem VARCHAR(30) NULL,
        data_processamento DATETIME2(3) NOT NULL
            CONSTRAINT DF_tb_log_integracao_data_processamento DEFAULT SYSUTCDATETIME(),
        CONSTRAINT PK_tb_log_integracao PRIMARY KEY CLUSTERED (id)
    );
END;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'occurrence_id') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD occurrence_id BIGINT NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'chave_nfe') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD chave_nfe VARCHAR(44) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'freight_id') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD freight_id BIGINT NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'cursor_next_id') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD cursor_next_id BIGINT NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'status') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD status VARCHAR(50) NOT NULL
        CONSTRAINT DF_tb_log_integracao_status DEFAULT 'RECEBIDO';
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'sistema_destino') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD sistema_destino VARCHAR(20) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'request_payload') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD request_payload NVARCHAR(MAX) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'response_payload') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD response_payload NVARCHAR(MAX) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'erro') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD erro NVARCHAR(MAX) NULL;
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

IF COL_LENGTH('dbo.tb_log_integracao', 'canhoto_referencia') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD canhoto_referencia NVARCHAR(2048) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'canhoto_mime_type') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD canhoto_mime_type VARCHAR(100) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'canhoto_origem') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD canhoto_origem VARCHAR(30) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'data_processamento') IS NULL
BEGIN
    ALTER TABLE dbo.tb_log_integracao ADD data_processamento DATETIME2(3) NULL;
    EXEC('UPDATE dbo.tb_log_integracao SET data_processamento = SYSUTCDATETIME() WHERE data_processamento IS NULL');
    ALTER TABLE dbo.tb_log_integracao ALTER COLUMN data_processamento DATETIME2(3) NOT NULL;
END;
GO
