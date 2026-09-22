USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

-- Adição de auditoria e agenda técnica. Não altera estados, aceites ou datas históricas.
IF COL_LENGTH('dbo.tb_log_integracao', 'dados_acao_operacional') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD dados_acao_operacional VARCHAR(40) NULL;
IF COL_LENGTH('dbo.tb_log_integracao', 'canhoto_acao_painel') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD canhoto_acao_painel VARCHAR(40) NULL;
IF COL_LENGTH('dbo.tb_log_integracao', 'dados_proxima_avaliacao_em') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD dados_proxima_avaliacao_em DATETIME2 NULL;
IF COL_LENGTH('dbo.tb_log_integracao', 'canhoto_proxima_avaliacao_em') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD canhoto_proxima_avaliacao_em DATETIME2 NULL;
IF COL_LENGTH('dbo.tb_log_integracao', 'dados_pendente_desde') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD dados_pendente_desde DATETIME2 NULL;
IF COL_LENGTH('dbo.tb_log_integracao', 'canhoto_pendente_desde') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD canhoto_pendente_desde DATETIME2 NULL;
IF COL_LENGTH('dbo.tb_work_sftp_cliente_execucao', 'resultado_execucao') IS NULL
    ALTER TABLE dbo.tb_work_sftp_cliente_execucao ADD resultado_execucao VARCHAR(40) NULL;
GO

IF OBJECT_ID('dbo.tb_integracao_etapa_evento', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.tb_integracao_etapa_evento (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_integracao_etapa_evento PRIMARY KEY,
        evento_id VARCHAR(36) NOT NULL CONSTRAINT UQ_integracao_etapa_evento_uuid UNIQUE,
        execucao_id VARCHAR(36) NULL,
        log_id BIGINT NULL,
        sistema_destino VARCHAR(20) NOT NULL,
        etapa VARCHAR(20) NOT NULL,
        documento VARCHAR(128) NOT NULL,
        tipo VARCHAR(30) NOT NULL,
        acao VARCHAR(40) NOT NULL,
        motivo VARCHAR(80) NOT NULL,
        ocorrido_em DATETIME2 NOT NULL,
        proxima_avaliacao_em DATETIME2 NULL,
        CONSTRAINT CK_integracao_evento_etapa CHECK (etapa IN ('DADOS', 'COMPROVANTE')),
        CONSTRAINT CK_integracao_evento_tipo CHECK (tipo IN ('AVALIACAO', 'ENVIO_INICIADO', 'CONSULTA_ORIGEM'))
    );
END;
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.tb_integracao_etapa_evento') AND name='IX_integracao_evento_periodo')
    CREATE INDEX IX_integracao_evento_periodo ON dbo.tb_integracao_etapa_evento(sistema_destino, ocorrido_em)
        INCLUDE (etapa, tipo, acao, motivo, documento, execucao_id);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.tb_integracao_etapa_evento') AND name='IX_integracao_evento_documento')
    CREATE INDEX IX_integracao_evento_documento ON dbo.tb_integracao_etapa_evento(sistema_destino, etapa, documento, tipo, ocorrido_em, id);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.tb_integracao_etapa_evento') AND name='IX_integracao_evento_log')
    CREATE INDEX IX_integracao_evento_log ON dbo.tb_integracao_etapa_evento(log_id, etapa, ocorrido_em) INCLUDE (tipo);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.tb_integracao_etapa_evento') AND name='IX_integracao_evento_espera')
    CREATE INDEX IX_integracao_evento_espera ON dbo.tb_integracao_etapa_evento(sistema_destino, motivo, proxima_avaliacao_em) INCLUDE (etapa, tipo);
GO

CREATE OR ALTER TRIGGER dbo.tr_integracao_evento_imutavel ON dbo.tb_integracao_etapa_evento
AFTER UPDATE, DELETE AS
BEGIN
    SET NOCOUNT ON;
    THROW 51028, 'Eventos de integracao sao imutaveis; preserve a auditoria.', 1;
END;
GO
