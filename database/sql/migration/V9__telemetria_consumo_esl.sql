:ON ERROR EXIT

USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF OBJECT_ID(N'dbo.tb_esl_request_telemetria', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.tb_esl_request_telemetria (
        id BIGINT IDENTITY(1,1) NOT NULL,
        data_evento DATETIME2(3) NOT NULL,
        origem VARCHAR(30) NOT NULL,
        destino VARCHAR(30) NOT NULL,
        rota VARCHAR(50) NOT NULL,
        template VARCHAR(80) NOT NULL,
        status_http INT NULL,
        tentativa INT NOT NULL,
        retry BIT NOT NULL,
        fallback BIT NOT NULL,
        cache_status VARCHAR(20) NOT NULL,
        duracao_ms BIGINT NOT NULL,
        CONSTRAINT PK_tb_esl_request_telemetria PRIMARY KEY CLUSTERED (id)
    );
END;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE object_id = OBJECT_ID(N'dbo.tb_esl_request_telemetria')
      AND name = N'IX_tb_esl_request_telemetria_periodo'
)
BEGIN
    CREATE INDEX IX_tb_esl_request_telemetria_periodo
        ON dbo.tb_esl_request_telemetria(data_evento DESC, rota, destino)
        INCLUDE (origem, status_http, tentativa, retry, fallback, cache_status, duracao_ms);
END;
GO
