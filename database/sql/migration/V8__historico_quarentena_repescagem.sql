:ON ERROR EXIT

USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF OBJECT_ID(N'dbo.tb_log_integracao_quarentena_evento', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.tb_log_integracao_quarentena_evento (
        id BIGINT IDENTITY(1,1) NOT NULL,
        log_integracao_id BIGINT NOT NULL,
        tipo_evento VARCHAR(30) NOT NULL,
        resultado VARCHAR(20) NOT NULL,
        etapa VARCHAR(20) NOT NULL,
        mensagem NVARCHAR(MAX) NULL,
        data_evento DATETIME2(3) NOT NULL,
        CONSTRAINT PK_tb_log_integracao_quarentena_evento PRIMARY KEY CLUSTERED (id),
        CONSTRAINT FK_tb_log_integracao_quarentena_evento_log
            FOREIGN KEY (log_integracao_id) REFERENCES dbo.tb_log_integracao(id)
    );
END;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE object_id = OBJECT_ID(N'dbo.tb_log_integracao_quarentena_evento')
      AND name = N'UX_tb_log_integracao_quarentena_entrada'
)
BEGIN
    CREATE UNIQUE INDEX UX_tb_log_integracao_quarentena_entrada
        ON dbo.tb_log_integracao_quarentena_evento(log_integracao_id, tipo_evento)
        WHERE tipo_evento = 'ENTRADA_QUARENTENA';
END;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE object_id = OBJECT_ID(N'dbo.tb_log_integracao_quarentena_evento')
      AND name = N'IX_tb_log_integracao_quarentena_evento_data'
)
BEGIN
    CREATE INDEX IX_tb_log_integracao_quarentena_evento_data
        ON dbo.tb_log_integracao_quarentena_evento(data_evento DESC, log_integracao_id);
END;
GO

INSERT INTO dbo.tb_log_integracao_quarentena_evento (
    log_integracao_id, tipo_evento, resultado, etapa, mensagem, data_evento
)
SELECT
    l.id,
    'ENTRADA_QUARENTENA',
    'PENDENTE',
    CASE
        WHEN l.status_dados = 'ERRO_DESTINO' AND l.status_canhoto = 'ERRO_DESTINO' THEN 'DADOS_E_COMPROVANTE'
        WHEN l.status_dados = 'ERRO_DESTINO' THEN 'DADOS'
        WHEN l.status_canhoto = 'ERRO_DESTINO' THEN 'COMPROVANTE'
        ELSE 'GERAL'
    END,
    COALESCE(l.mensagem_erro_dados, l.mensagem_erro_canhoto, l.erro),
    l.data_processamento
FROM dbo.tb_log_integracao l
WHERE l.status = 'ERRO_DESTINO'
  AND (ISNULL(l.tentativas_dados, 0) >= 3 OR ISNULL(l.tentativas_canhoto, 0) >= 3)
  AND NOT EXISTS (
      SELECT 1
      FROM dbo.tb_log_integracao_quarentena_evento e
      WHERE e.log_integracao_id = l.id
        AND e.tipo_evento = 'ENTRADA_QUARENTENA'
  );
GO
