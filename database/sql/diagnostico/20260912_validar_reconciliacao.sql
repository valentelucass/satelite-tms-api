USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
SELECT 'estrutura' consulta,t.name tabela,COUNT_BIG(c.column_id) colunas
FROM sys.tables t JOIN sys.columns c ON c.object_id=t.object_id
WHERE t.name IN ('tb_reconciliacao_vedacit_execucao','tb_reconciliacao_vedacit_item') GROUP BY t.name;
SELECT 'execucoes' consulta,COUNT_BIG(*) registros FROM dbo.tb_reconciliacao_vedacit_execucao;
SELECT 'itens' consulta,COUNT_BIG(*) registros FROM dbo.tb_reconciliacao_vedacit_item;
SELECT 'logs_preservados' consulta,COUNT_BIG(*) registros FROM dbo.tb_log_integracao;
SELECT 'aceites_preservados' consulta,COUNT_BIG(*) registros FROM dbo.tb_confirmacao_comprovante;
