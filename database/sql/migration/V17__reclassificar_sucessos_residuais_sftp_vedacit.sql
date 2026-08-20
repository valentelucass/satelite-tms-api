USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

/*
   Fecha apenas classificações residuais: ambos os estágios já foram
   confirmados como sucesso e o registro pertence à fila SFTP Vedacit.
   Não envia, remove, arquiva ou altera documento/payload.
*/
UPDATE dbo.tb_log_integracao
SET canhoto_classificacao_operacional = 'SUCESSO',
    canhoto_classificado_em = SYSDATETIME()
WHERE sistema_destino = 'VEDACIT'
  AND sftp_cliente = 'VEDACIT'
  AND status_dados = 'SUCESSO'
  AND status_canhoto = 'SUCESSO'
  AND COALESCE(canhoto_classificacao_operacional, '') <> 'SUCESSO';
GO
