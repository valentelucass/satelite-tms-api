USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

/* Rejeicao sem NF-e/CT-e nao e falha tecnica recuperavel. */
UPDATE dbo.tb_log_integracao
SET canhoto_classificacao_operacional = 'BLOQUEADO_ORIGEM',
    canhoto_classificado_em = SYSDATETIME()
WHERE sistema_destino = 'VEDACIT'
  AND sftp_cliente = 'VEDACIT'
  AND status_dados = 'SUCESSO'
  AND status_canhoto = 'ERRO_DESTINO'
  AND canhoto_classificacao_operacional = 'PENDENTE_TECNICO'
  AND LEN(COALESCE(mensagem_erro_canhoto, erro, '')) = 44
  AND LOWER(COALESCE(mensagem_erro_canhoto, erro, '')) LIKE 'arquivo inv%lido: nome sem nf-e/ct-e v%lidos';
GO
