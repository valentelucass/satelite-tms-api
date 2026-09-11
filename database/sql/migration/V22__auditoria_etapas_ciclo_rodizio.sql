:ON ERROR EXIT
USE [$(DatabaseName)];
GO
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
IF LOWER(DB_NAME()) <> N'satelite_tms_auditoria'
    THROW 50001, 'Database fora do escopo autorizado.', 1;
GO
-- NULL preserva a ausência de medição nos ciclos anteriores. Não inventar contagens históricas.
IF COL_LENGTH('dbo.tb_work_sftp_cliente_execucao', 'xml_habilitado') IS NULL
    ALTER TABLE dbo.tb_work_sftp_cliente_execucao ADD xml_habilitado BIT NULL;
IF COL_LENGTH('dbo.tb_work_sftp_cliente_execucao', 'xml_avaliados') IS NULL
    ALTER TABLE dbo.tb_work_sftp_cliente_execucao ADD xml_avaliados INT NULL;
IF COL_LENGTH('dbo.tb_work_sftp_cliente_execucao', 'xml_enviados') IS NULL
    ALTER TABLE dbo.tb_work_sftp_cliente_execucao ADD xml_enviados INT NULL;
IF COL_LENGTH('dbo.tb_work_sftp_cliente_execucao', 'xml_ja_processados') IS NULL
    ALTER TABLE dbo.tb_work_sftp_cliente_execucao ADD xml_ja_processados INT NULL;
IF COL_LENGTH('dbo.tb_work_sftp_cliente_execucao', 'xml_pendentes') IS NULL
    ALTER TABLE dbo.tb_work_sftp_cliente_execucao ADD xml_pendentes INT NULL;
IF COL_LENGTH('dbo.tb_work_sftp_cliente_execucao', 'xml_erros') IS NULL
    ALTER TABLE dbo.tb_work_sftp_cliente_execucao ADD xml_erros INT NULL;
IF COL_LENGTH('dbo.tb_work_sftp_cliente_execucao', 'erros_comprovante') IS NULL
    ALTER TABLE dbo.tb_work_sftp_cliente_execucao ADD erros_comprovante INT NULL;
IF COL_LENGTH('dbo.tb_work_sftp_cliente_execucao', 'motivo_falha') IS NULL
    ALTER TABLE dbo.tb_work_sftp_cliente_execucao ADD motivo_falha NVARCHAR(240) NULL;
GO
