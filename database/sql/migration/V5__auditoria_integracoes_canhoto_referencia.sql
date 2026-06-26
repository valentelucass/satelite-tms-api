:ON ERROR EXIT

USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
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

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = N'IX_tb_log_integracao_auditoria_escopo'
      AND object_id = OBJECT_ID(N'dbo.tb_log_integracao')
)
BEGIN
    CREATE NONCLUSTERED INDEX IX_tb_log_integracao_auditoria_escopo
    ON dbo.tb_log_integracao (sistema_destino, status_canhoto, status_dados, data_processamento DESC, id DESC)
    INCLUDE (
        occurrence_id,
        freight_id,
        chave_nfe,
        mensagem_erro_dados,
        mensagem_erro_canhoto,
        canhoto_referencia,
        canhoto_mime_type
    );
END;
GO
