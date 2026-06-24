:ON ERROR EXIT

USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

DECLARE @RetentionDays INT = 90;
DECLARE @Cutoff DATETIME2(3) = DATEADD(DAY, -@RetentionDays, SYSUTCDATETIME());

SELECT
    COUNT_BIG(*) AS registros_candidatos_para_limpeza,
    MIN(data_processamento) AS primeiro_registro,
    MAX(data_processamento) AS ultimo_registro
FROM dbo.tb_log_integracao
WHERE data_processamento < @Cutoff;
GO

/*
    Este arquivo e apenas uma pre-visualizacao de retencao.
    Para apagar de fato, crie um job controlado e auditado, por exemplo:

    DELETE TOP (1000)
    FROM dbo.tb_log_integracao
    WHERE data_processamento < DATEADD(DAY, -90, SYSUTCDATETIME());

    Rode em lotes pequenos para nao travar a tabela.
*/
