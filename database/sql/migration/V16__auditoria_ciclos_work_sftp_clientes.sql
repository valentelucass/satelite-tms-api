USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF OBJECT_ID(N'dbo.tb_work_sftp_cliente_execucao', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.tb_work_sftp_cliente_execucao (
        id BIGINT IDENTITY(1,1) NOT NULL,
        sftp_cliente VARCHAR(64) NOT NULL,
        inicio_em DATETIME2(3) NOT NULL,
        fim_em DATETIME2(3) NOT NULL,
        conexao VARCHAR(20) NOT NULL,
        status_ciclo VARCHAR(20) NOT NULL,
        arquivos_validos INT NOT NULL,
        arquivos_rejeitados INT NOT NULL,
        selecionados INT NOT NULL,
        enviados INT NOT NULL,
        pendentes INT NOT NULL,
        saldo BIGINT NOT NULL,
        bloqueios BIGINT NOT NULL,
        timeouts_ambiguos BIGINT NOT NULL,
        duracao_ms BIGINT NOT NULL,
        CONSTRAINT PK_tb_work_sftp_cliente_execucao PRIMARY KEY CLUSTERED (id)
    );
END;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE object_id = OBJECT_ID(N'dbo.tb_work_sftp_cliente_execucao')
      AND name = N'IX_tb_work_sftp_cliente_execucao_ultimo_ciclo'
)
BEGIN
    CREATE NONCLUSTERED INDEX IX_tb_work_sftp_cliente_execucao_ultimo_ciclo
        ON dbo.tb_work_sftp_cliente_execucao (sftp_cliente, fim_em DESC, id DESC)
        INCLUDE (inicio_em, conexao, status_ciclo, arquivos_validos, arquivos_rejeitados,
                 selecionados, enviados, pendentes, saldo, bloqueios, timeouts_ambiguos, duracao_ms);
END;
GO
