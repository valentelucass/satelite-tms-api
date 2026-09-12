USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Somente estado tecnico. Nao altera logs, aceites, documentos nem arquivamentos existentes.
IF OBJECT_ID('dbo.tb_reconciliacao_vedacit_execucao','U') IS NULL
BEGIN
    CREATE TABLE dbo.tb_reconciliacao_vedacit_execucao (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_reconciliacao_vedacit_execucao PRIMARY KEY,
        dia_referencia DATE NOT NULL,
        inicio_em DATETIME2 NOT NULL,
        atualizado_em DATETIME2 NOT NULL,
        fim_em DATETIME2 NULL,
        status VARCHAR(30) NOT NULL,
        ultimo_log_id BIGINT NOT NULL CONSTRAINT DF_reconciliacao_cursor DEFAULT 0,
        limite_log_id BIGINT NOT NULL,
        bloqueio_sftp VARCHAR(60) NULL,
        bloqueio_consulta_xml VARCHAR(60) NULL,
        bloqueio_consulta_pod VARCHAR(60) NULL,
        bloqueio_origem_xml VARCHAR(60) NULL,
        bloqueio_envio_xml VARCHAR(60) NULL,
        bloqueio_envio_pod VARCHAR(60) NULL,
        fontes_dia DATE NOT NULL,
        motivo VARCHAR(200) NULL,
        retomadas INT NOT NULL CONSTRAINT DF_reconciliacao_retomadas DEFAULT 0,
        CONSTRAINT UQ_reconciliacao_vedacit_dia UNIQUE(dia_referencia)
    );
END;
IF OBJECT_ID('dbo.tb_reconciliacao_vedacit_item','U') IS NULL
BEGIN
    CREATE TABLE dbo.tb_reconciliacao_vedacit_item (
        execucao_id BIGINT NOT NULL,
        log_id BIGINT NOT NULL,
        revisado_em DATETIME2 NOT NULL,
        proxima_revisao_em DATETIME2 NOT NULL,
        arquivado BIT NOT NULL,
        resultado_xml VARCHAR(70) NOT NULL,
        resultado_comprovante VARCHAR(70) NOT NULL,
        acao VARCHAR(70) NOT NULL,
        detalhe NVARCHAR(500) NOT NULL,
        CONSTRAINT PK_reconciliacao_vedacit_item PRIMARY KEY(execucao_id,log_id),
        CONSTRAINT FK_reconciliacao_item_execucao FOREIGN KEY(execucao_id)
            REFERENCES dbo.tb_reconciliacao_vedacit_execucao(id),
        CONSTRAINT FK_reconciliacao_item_log FOREIGN KEY(log_id) REFERENCES dbo.tb_log_integracao(id)
    );
END;
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.tb_reconciliacao_vedacit_item')
               AND name='IX_reconciliacao_item_log')
    CREATE INDEX IX_reconciliacao_item_log ON dbo.tb_reconciliacao_vedacit_item(log_id,revisado_em DESC)
        INCLUDE(resultado_xml,resultado_comprovante,acao,proxima_revisao_em);
GO
