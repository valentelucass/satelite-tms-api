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

IF COL_LENGTH('dbo.tb_log_integracao', 'data_processamento') IS NULL
BEGIN
    ALTER TABLE dbo.tb_log_integracao ADD data_processamento DATETIME2(3) NULL;
    EXEC('UPDATE dbo.tb_log_integracao SET data_processamento = SYSUTCDATETIME() WHERE data_processamento IS NULL');
    ALTER TABLE dbo.tb_log_integracao ALTER COLUMN data_processamento DATETIME2(3) NOT NULL;
END;
GO
