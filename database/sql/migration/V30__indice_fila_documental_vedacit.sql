USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET NUMERIC_ROUNDABORT OFF;
GO
-- A fila e o saldo pesquisam somente as notas pendentes desta etapa do envio.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.tb_log_integracao')
    AND name='IX_tb_log_integracao_sftp_pendentes')
    CREATE NONCLUSTERED INDEX IX_tb_log_integracao_sftp_pendentes
        ON dbo.tb_log_integracao(sistema_destino,status_dados,status_canhoto,canhoto_classificacao_operacional,chave_nfe)
        INCLUDE(sftp_cliente,data_processamento,chave_cte,arquivado,canhoto_proxima_avaliacao_em)
        WHERE sistema_destino='VEDACIT' AND status_dados='SUCESSO' AND status_canhoto='PENDENTE_FOTO'
          AND canhoto_classificacao_operacional='PENDENTE_ENVIO' WITH(MAXDOP=1);
GO
