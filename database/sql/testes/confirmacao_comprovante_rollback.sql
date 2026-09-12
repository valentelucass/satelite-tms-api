USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Executar exclusivamente pelo runner com -Rollback. Todos os registros são sintéticos.
IF @@TRANCOUNT=0 THROW 51026,'Teste exige transacao externa e rollback.',1;
IF EXISTS (SELECT 1 FROM dbo.tb_log_integracao WHERE chave_nfe=REPLICATE('8',44))
    THROW 51026,'Chave sintetica ja existe: teste abortado.',1;
DECLARE @nfe VARCHAR(44)=REPLICATE('8',44), @cte VARCHAR(44)=REPLICATE('9',44);
INSERT INTO dbo.tb_log_integracao (sistema_destino,status,status_dados,status_canhoto,chave_nfe,chave_cte,data_processamento_canhoto)
VALUES ('VEDACIT','ENVIADO','SUCESSO','SUCESSO',@nfe,@cte,'2026-08-20T12:00:00');
DECLARE @id BIGINT=SCOPE_IDENTITY();
-- Repetir conciliação XML e arquivar não desloca a primeira confirmação.
DECLARE @i INT=0;
WHILE @i<1000
BEGIN
    UPDATE dbo.tb_log_integracao SET data_processamento_dados=SYSDATETIME(), status_dados='SUCESSO' WHERE id=@id;
    SET @i+=1;
END;
UPDATE dbo.tb_log_integracao SET arquivado=1 WHERE id=@id;
INSERT INTO dbo.tb_log_integracao (sistema_destino,status,status_canhoto,chave_nfe,chave_cte,data_processamento_canhoto)
VALUES ('VEDACIT','ENVIADO','SUCESSO',@nfe,@cte,'2026-09-11T12:00:00');
IF (SELECT COUNT(*) FROM dbo.tb_confirmacao_comprovante WHERE chave_nfe=@nfe AND chave_cte=@cte)<>1
    THROW 51026,'Aceite duplicado no registro permanente.',1;
IF NOT EXISTS (SELECT 1 FROM dbo.tb_confirmacao_comprovante WHERE chave_nfe=@nfe AND chave_cte=@cte
    AND primeira_confirmacao_em='2026-08-20T12:00:00' AND ultima_confirmacao_em='2026-09-11T12:00:00')
    THROW 51026,'Primeira confirmacao deslocada.',1;
-- CT-e efetivo constitui outro documento; várias linhas por statement são suportadas.
INSERT INTO dbo.tb_log_integracao (sistema_destino,status,status_canhoto,chave_nfe,chave_cte,canhoto_chave_cte_efetiva,data_processamento_canhoto)
VALUES ('VEDACIT','ENVIADO','SUCESSO',@nfe,@cte,REPLICATE('7',44),NULL),
       ('VEDACIT','ENVIADO','SUCESSO',@nfe,@cte,REPLICATE('6',44),'2026-09-10T12:00:00');
IF (SELECT COUNT(*) FROM dbo.tb_confirmacao_comprovante WHERE chave_nfe=@nfe)<>3
    THROW 51026,'Par efetivo foi misturado.',1;
IF NOT EXISTS (SELECT 1 FROM dbo.tb_confirmacao_comprovante WHERE chave_nfe=@nfe AND chave_cte=REPLICATE('7',44)
    AND primeira_confirmacao_em IS NULL AND data_confiavel=0)
    THROW 51026,'Data de aceite desconhecida foi inventada.',1;
-- Uma nova observação não resolve automaticamente a incerteza histórica.
INSERT INTO dbo.tb_log_integracao (sistema_destino,status,status_canhoto,chave_nfe,chave_cte,data_processamento_canhoto)
VALUES ('VEDACIT','ENVIADO','SUCESSO',@nfe,REPLICATE('7',44),'2026-09-11T12:00:00');
IF NOT EXISTS (SELECT 1 FROM dbo.tb_confirmacao_comprovante WHERE chave_nfe=@nfe AND chave_cte=REPLICATE('7',44)
    AND primeira_confirmacao_em IS NULL AND data_confiavel=0)
    THROW 51026,'Incerteza historica apagada.',1;
SELECT '1000 conciliacoes, arquivamento, duplicacao, CT-e efetivo, insercao multipla e data incerta' AS casos, 'PASS' AS resultado;
GO
