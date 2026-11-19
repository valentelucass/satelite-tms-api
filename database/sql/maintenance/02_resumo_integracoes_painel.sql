USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Resumo das etapas para os quatro destinos. A assinatura identifica o SQL enviado pelo JDBC.
DECLARE @alvo TABLE(id bigint PRIMARY KEY);
INSERT @alvo(id)
SELECT q.query_id
FROM sys.query_store_query q
JOIN sys.query_store_query_text t ON t.query_text_id=q.query_text_id
WHERE q.query_hash=0xF63E6870C3A21B7F
  AND HASHBYTES('SHA2_256',t.query_sql_text)=0xF5389D0FD41AEC36522565E0A97E424E92C214C303D6490D7E1A47597A6A0387;
IF (SELECT COUNT(*) FROM @alvo)<>1
    THROW 52401,'Conferir a assinatura da consulta de resumo no Query Store.',1;
DECLARE @id bigint=(SELECT id FROM @alvo);
DECLARE @atual nvarchar(max)=(SELECT query_hint_text FROM sys.query_store_query_hints WHERE query_id=@id);
IF @atual IS NOT NULL AND REPLACE(UPPER(@atual),' ','')<>N'OPTION(MAXDOP1)'
    THROW 52402,'A consulta ja possui outro ajuste de plano.',1;
IF @atual IS NULL
    EXEC sys.sp_query_store_set_hints @query_id=@id,@query_hints=N'OPTION (MAXDOP 1)';
IF NOT EXISTS(SELECT 1 FROM sys.query_store_query_hints WHERE query_id=@id
              AND REPLACE(UPPER(query_hint_text),' ','')=N'OPTION(MAXDOP1)')
    THROW 52403,'Ajuste do resumo sem confirmacao.',1;
