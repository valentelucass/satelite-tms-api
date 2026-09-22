USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Conferência local dos demais clientes. Histórico e checkpoint Vedacit permanecem intactos.
IF OBJECT_ID('dbo.tb_reconciliacao_cliente_execucao','U') IS NULL
BEGIN
    CREATE TABLE dbo.tb_reconciliacao_cliente_execucao (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_reconciliacao_cliente_execucao PRIMARY KEY,
        cliente VARCHAR(20) NOT NULL,
        dia_referencia DATE NOT NULL,
        inicio_em DATETIME2 NOT NULL,
        atualizado_em DATETIME2 NOT NULL,
        fim_em DATETIME2 NULL,
        status VARCHAR(30) NOT NULL,
        ultimo_log_id BIGINT NOT NULL CONSTRAINT DF_reconciliacao_cliente_cursor DEFAULT 0,
        limite_log_id BIGINT NOT NULL,
        CONSTRAINT UQ_reconciliacao_cliente_dia UNIQUE(cliente,dia_referencia)
    );
END;
IF OBJECT_ID('dbo.tb_reconciliacao_cliente_item','U') IS NULL
BEGIN
    CREATE TABLE dbo.tb_reconciliacao_cliente_item (
        execucao_id BIGINT NOT NULL,
        log_id BIGINT NOT NULL,
        revisado_em DATETIME2 NOT NULL,
        proxima_revisao_em DATETIME2 NOT NULL,
        arquivado BIT NOT NULL,
        resultado_dados VARCHAR(70) NOT NULL,
        resultado_comprovante VARCHAR(70) NOT NULL,
        acao VARCHAR(70) NOT NULL,
        CONSTRAINT PK_reconciliacao_cliente_item PRIMARY KEY(execucao_id,log_id),
        CONSTRAINT FK_reconciliacao_cliente_item_execucao FOREIGN KEY(execucao_id)
            REFERENCES dbo.tb_reconciliacao_cliente_execucao(id),
        CONSTRAINT FK_reconciliacao_cliente_item_log FOREIGN KEY(log_id) REFERENCES dbo.tb_log_integracao(id)
    );
END;
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.tb_reconciliacao_cliente_item')
    AND name='IX_reconciliacao_cliente_item_log')
    CREATE INDEX IX_reconciliacao_cliente_item_log ON dbo.tb_reconciliacao_cliente_item(log_id,revisado_em DESC)
        INCLUDE(resultado_dados,resultado_comprovante,acao,proxima_revisao_em);
GO
