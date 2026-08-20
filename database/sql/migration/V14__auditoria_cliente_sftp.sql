USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

/* Identifica a origem SFTP sem alterar ou reclassificar o histórico existente. */
IF COL_LENGTH('dbo.tb_log_integracao', 'sftp_cliente') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD sftp_cliente VARCHAR(64) NULL;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = N'IX_tb_log_integracao_vedacit_cliente_fila'
      AND object_id = OBJECT_ID(N'dbo.tb_log_integracao')
)
BEGIN
    CREATE NONCLUSTERED INDEX IX_tb_log_integracao_vedacit_cliente_fila
        ON dbo.tb_log_integracao (
            sistema_destino,
            sftp_cliente,
            status_dados,
            status_canhoto,
            data_processamento DESC,
            id DESC
        )
        INCLUDE (chave_nfe, chave_cte, canhoto_referencia, canhoto_classificacao_operacional);
END;
GO
