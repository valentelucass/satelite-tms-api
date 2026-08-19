USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

/* Falha transitória do destino/intermediário; não é pendência de arquivo. */
UPDATE dbo.tb_log_integracao
SET canhoto_classificacao_operacional = 'PENDENTE_TECNICO',
    canhoto_classificado_em = SYSDATETIME()
WHERE sistema_destino = 'VEDACIT'
  AND status_canhoto = 'ERRO_DESTINO'
  AND LOWER(COALESCE(mensagem_erro_canhoto, erro, '')) LIKE '%código de status http 520%'
  AND COALESCE(canhoto_classificacao_operacional, '') <> 'PENDENTE_TECNICO';
GO
