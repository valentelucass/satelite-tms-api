USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
IF @@TRANCOUNT=0 THROW 51026,'Teste exige rollback externo.',1;
UPDATE dbo.tb_log_integracao SET data_processamento_canhoto='2026-09-11T12:00:00' WHERE chave_nfe=REPLICATE('8',44) AND chave_cte=REPLICATE('9',44) AND arquivado=1;
GO
