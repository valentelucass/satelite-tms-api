USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
SET NOCOUNT ON;

-- Somente leitura. Executar com scripts/executar_sql_auditoria.ps1 -Rollback.
-- A data de entrega verdadeira vem da ocorrência ESL 1; não está neste registro.
SELECT
    l.id,
    SUBSTRING(l.chave_nfe, 26, 9) AS numero_nfe,
    SUBSTRING(l.chave_cte, 26, 9) AS numero_cte,
    l.occurrence_id,
    l.sftp_cliente,
    l.canhoto_origem,
    l.status_dados,
    l.status_canhoto,
    l.canhoto_classificacao_operacional,
    l.tentativas_canhoto,
    CONVERT(varchar(33), l.data_processamento_dados, 126) AS data_processamento_dados,
    CONVERT(varchar(33), l.data_processamento_canhoto, 126) AS data_processamento_canhoto,
    CONVERT(varchar(33), c.primeira_confirmacao_em, 126) AS primeira_confirmacao_em,
    c.data_confiavel AS data_confirmacao_confiavel,
    DATALENGTH(l.request_payload) AS request_bytes,
    DATALENGTH(l.response_payload) AS response_bytes
FROM dbo.tb_log_integracao l
LEFT JOIN dbo.tb_confirmacao_comprovante c
    ON c.chave_nfe = l.chave_nfe
    AND c.chave_cte = COALESCE(NULLIF(l.canhoto_chave_cte_efetiva, ''), l.chave_cte)
WHERE l.id = 71230
    AND l.sistema_destino = 'VEDACIT'
    AND COALESCE(l.arquivado, 0) = 0;
