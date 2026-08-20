USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

/* A única fila SFTP histórica era Vedacit; preserva estado e apenas identifica a origem. */
UPDATE dbo.tb_log_integracao
SET sftp_cliente = 'VEDACIT'
WHERE sistema_destino = 'VEDACIT'
  AND sftp_cliente IS NULL
  AND (
        canhoto_origem = 'SFTP'
        OR canhoto_reconciliacao_tipo IS NOT NULL
      );
GO
