USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

/*
   Corrige somente auditorias Vedacit cujo próprio resultado de canhoto já
   registrou timeout de leitura. Não envia, não remove e não altera o SFTP.
*/
UPDATE dbo.tb_log_integracao
SET canhoto_classificacao_operacional = 'TIMEOUT_AMBIGUO',
    canhoto_classificado_em = SYSDATETIME()
WHERE sistema_destino = 'VEDACIT'
  AND status_canhoto = 'ERRO_DESTINO'
  AND LOWER(COALESCE(mensagem_erro_canhoto, erro, '')) LIKE '%read timed out%'
  AND COALESCE(canhoto_classificacao_operacional, '') <> 'TIMEOUT_AMBIGUO';
GO
