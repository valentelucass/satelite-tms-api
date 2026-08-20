USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

/* Comprovante SFTP sem XML/CT-e confirmado nao pode entrar na quarentena tecnica. */
UPDATE dbo.tb_log_integracao
SET canhoto_classificacao_operacional = 'BLOQUEADO_ORIGEM',
    canhoto_classificado_em = SYSDATETIME()
WHERE sistema_destino = 'VEDACIT'
  AND sftp_cliente = 'VEDACIT'
  AND status_dados = 'PENDENTE_ORIGEM'
  AND status_canhoto = 'PENDENTE_FOTO'
  AND canhoto_classificacao_operacional = 'PENDENTE_TECNICO';
GO
