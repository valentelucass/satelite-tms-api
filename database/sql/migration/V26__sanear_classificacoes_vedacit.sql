USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
SET XACT_ABORT ON;
BEGIN TRANSACTION;
GO
-- Correções apoiadas em evidência existente. Não habilita XML, reenvia ou arquiva logs.
IF OBJECT_ID('dbo.tb_ajuste_estado_vedacit','U') IS NULL
BEGIN
    CREATE TABLE dbo.tb_ajuste_estado_vedacit (
        ajuste VARCHAR(60) NOT NULL,
        log_id BIGINT NOT NULL,
        estado_anterior NVARCHAR(MAX) NOT NULL,
        registrado_em DATETIME2 NOT NULL CONSTRAINT DF_ajuste_estado_vedacit_data DEFAULT SYSDATETIME(),
        CONSTRAINT PK_ajuste_estado_vedacit PRIMARY KEY (ajuste, log_id)
    );
END;
GO
INSERT INTO dbo.tb_ajuste_estado_vedacit (ajuste,log_id,estado_anterior)
SELECT 'V26_XML_NAO_APLICAVEL_ARQUIVO_SEM_IDENTIDADE',l.id,
       (SELECT l.status,l.status_dados,l.status_canhoto,l.data_processamento_dados,
               l.data_processamento_canhoto,l.canhoto_classificacao_operacional,
               l.canhoto_classificado_em,l.data_processamento FOR JSON PATH,WITHOUT_ARRAY_WRAPPER,INCLUDE_NULL_VALUES)
FROM dbo.tb_log_integracao l
WHERE l.sistema_destino='VEDACIT' AND l.sftp_cliente='VEDACIT' AND (l.arquivado=0 OR l.arquivado IS NULL)
  AND NULLIF(l.chave_cte,'') IS NULL AND NULLIF(l.chave_nfe,'') IS NULL
  AND l.status_dados='SUCESSO' AND l.data_processamento_dados IS NULL AND COALESCE(l.tentativas_dados,0)=0
  AND l.status_canhoto='ERRO_DESTINO' AND l.canhoto_classificacao_operacional='BLOQUEADO_ORIGEM'
  AND l.canhoto_referencia IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM dbo.tb_ajuste_estado_vedacit m
                  WHERE m.ajuste='V26_XML_NAO_APLICAVEL_ARQUIVO_SEM_IDENTIDADE' AND m.log_id=l.id);

UPDATE l SET status_dados='NAO_APLICAVEL'
FROM dbo.tb_log_integracao l JOIN dbo.tb_ajuste_estado_vedacit m ON m.log_id=l.id
 AND m.ajuste='V26_XML_NAO_APLICAVEL_ARQUIVO_SEM_IDENTIDADE'
WHERE l.sistema_destino='VEDACIT' AND l.sftp_cliente='VEDACIT' AND (l.arquivado=0 OR l.arquivado IS NULL)
  AND NULLIF(l.chave_cte,'') IS NULL AND NULLIF(l.chave_nfe,'') IS NULL
  AND l.status_dados='SUCESSO' AND l.data_processamento_dados IS NULL AND COALESCE(l.tentativas_dados,0)=0
  AND l.status_canhoto='ERRO_DESTINO' AND l.canhoto_classificacao_operacional='BLOQUEADO_ORIGEM';
GO
-- O aceite permanente identifica exatamente NF-e/CT-e; não depende de inferência pela NF-e.
INSERT INTO dbo.tb_ajuste_estado_vedacit (ajuste,log_id,estado_anterior)
SELECT 'V26_COMPROVANTE_CONCILIADO_ACEITE_PERMANENTE',l.id,
       (SELECT l.status,l.status_dados,l.status_canhoto,l.data_processamento_dados,
               l.data_processamento_canhoto,l.canhoto_classificacao_operacional,
               l.canhoto_classificado_em,l.mensagem_erro_canhoto,l.data_processamento
        FOR JSON PATH,WITHOUT_ARRAY_WRAPPER,INCLUDE_NULL_VALUES)
FROM dbo.tb_log_integracao l JOIN dbo.tb_confirmacao_comprovante c
 ON c.chave_nfe=l.chave_nfe AND c.chave_cte=COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte)
WHERE l.sistema_destino='VEDACIT' AND l.sftp_cliente='VEDACIT' AND (l.arquivado=0 OR l.arquivado IS NULL)
  AND l.status_canhoto='PENDENTE_FOTO' AND l.canhoto_classificacao_operacional IN ('PENDENTE_ENVIO','BLOQUEADO_ORIGEM')
  AND NOT EXISTS (SELECT 1 FROM dbo.tb_ajuste_estado_vedacit m
                  WHERE m.ajuste='V26_COMPROVANTE_CONCILIADO_ACEITE_PERMANENTE' AND m.log_id=l.id);

UPDATE l SET status_canhoto='SUCESSO', data_processamento_canhoto=c.primeira_confirmacao_em,
             canhoto_classificacao_operacional='SUCESSO', canhoto_classificado_em=SYSDATETIME(),
             mensagem_erro_canhoto=NULL,
             status=CASE WHEN l.status_dados='SUCESSO' THEN 'ENVIADO' ELSE 'PARCIAL' END
FROM dbo.tb_log_integracao l JOIN dbo.tb_confirmacao_comprovante c
 ON c.chave_nfe=l.chave_nfe AND c.chave_cte=COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte)
JOIN dbo.tb_ajuste_estado_vedacit m ON m.log_id=l.id AND m.ajuste='V26_COMPROVANTE_CONCILIADO_ACEITE_PERMANENTE'
WHERE l.sistema_destino='VEDACIT' AND l.sftp_cliente='VEDACIT' AND (l.arquivado=0 OR l.arquivado IS NULL)
  AND l.status_canhoto='PENDENTE_FOTO' AND l.canhoto_classificacao_operacional IN ('PENDENTE_ENVIO','BLOQUEADO_ORIGEM');
GO
SELECT ajuste, COUNT_BIG(*) registros_preservados FROM dbo.tb_ajuste_estado_vedacit
WHERE ajuste LIKE 'V26_%' GROUP BY ajuste;
COMMIT TRANSACTION;
