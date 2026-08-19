USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'canhoto_classificacao_operacional') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD canhoto_classificacao_operacional VARCHAR(40) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'canhoto_classificado_em') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD canhoto_classificado_em DATETIME2 NULL;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_tb_log_integracao_vedacit_canhoto_classificacao' AND object_id = OBJECT_ID(N'dbo.tb_log_integracao'))
    CREATE NONCLUSTERED INDEX IX_tb_log_integracao_vedacit_canhoto_classificacao
        ON dbo.tb_log_integracao (sistema_destino, canhoto_classificacao_operacional, status_canhoto)
        INCLUDE (chave_nfe, chave_cte, tentativas_canhoto, data_processamento_canhoto);
GO

UPDATE dbo.tb_log_integracao
SET canhoto_classificacao_operacional = CASE
        WHEN status_canhoto = 'SUCESSO' THEN 'SUCESSO'
        WHEN status_canhoto = 'PENDENTE_FOTO' AND (chave_cte IS NULL OR LTRIM(RTRIM(chave_cte)) = '') THEN 'BLOQUEADO_ORIGEM'
        WHEN status_canhoto = 'PENDENTE_FOTO' THEN 'PENDENTE_ENVIO'
        WHEN status_canhoto = 'ERRO_DESTINO'
             AND LOWER(COALESCE(mensagem_erro_canhoto, erro, '')) LIKE '%read timed out%' THEN 'TIMEOUT_AMBIGUO'
        WHEN status_canhoto = 'ERRO_DESTINO'
             AND (LOWER(COALESCE(mensagem_erro_canhoto, erro, '')) LIKE '%vedacit recusou%'
                  OR LOWER(COALESCE(mensagem_erro_canhoto, erro, '')) LIKE '%canhoto compativel%') THEN 'BLOQUEADO_DESTINO'
        WHEN status_canhoto = 'ERRO_DESTINO'
             AND (LOWER(COALESCE(mensagem_erro_canhoto, erro, '')) LIKE '%formato de imagem%'
                  OR LOWER(COALESCE(mensagem_erro_canhoto, erro, '')) LIKE '%chave%') THEN 'BLOQUEADO_ORIGEM'
        WHEN status_canhoto = 'ERRO_DESTINO' THEN 'PENDENTE_TECNICO'
        ELSE canhoto_classificacao_operacional
    END,
    canhoto_classificado_em = COALESCE(canhoto_classificado_em, SYSDATETIME())
WHERE sistema_destino = 'VEDACIT'
  AND canhoto_classificacao_operacional IS NULL
  AND status_canhoto IN ('SUCESSO', 'PENDENTE_FOTO', 'ERRO_DESTINO');
GO
