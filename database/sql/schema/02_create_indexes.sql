:ON ERROR EXIT

USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = N'IX_tb_log_integracao_chave_nfe'
      AND object_id = OBJECT_ID(N'dbo.tb_log_integracao')
)
BEGIN
    CREATE INDEX IX_tb_log_integracao_chave_nfe
    ON dbo.tb_log_integracao (chave_nfe);
END;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = N'IX_tb_log_integracao_destino_occurrence_status'
      AND object_id = OBJECT_ID(N'dbo.tb_log_integracao')
)
BEGIN
    CREATE INDEX IX_tb_log_integracao_destino_occurrence_status
    ON dbo.tb_log_integracao (sistema_destino, occurrence_id, status)
    INCLUDE (chave_nfe, cursor_next_id, data_processamento);
END;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = N'IX_tb_log_integracao_cursor_destino_status'
      AND object_id = OBJECT_ID(N'dbo.tb_log_integracao')
)
BEGIN
    CREATE INDEX IX_tb_log_integracao_cursor_destino_status
    ON dbo.tb_log_integracao (sistema_destino, status, data_processamento DESC, id DESC)
    INCLUDE (cursor_next_id)
    WHERE cursor_next_id IS NOT NULL;
END;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = N'IX_tb_log_integracao_status_destino'
      AND object_id = OBJECT_ID(N'dbo.tb_log_integracao')
)
BEGIN
    CREATE INDEX IX_tb_log_integracao_status_destino
    ON dbo.tb_log_integracao (status, sistema_destino, data_processamento DESC)
    INCLUDE (occurrence_id, chave_nfe);
END;
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
