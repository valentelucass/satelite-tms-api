USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
SELECT SYSDATETIME() AS corte, COUNT(*) AS aceites,
    SUM(CASE WHEN data_processamento_canhoto >= '20260911' THEN 1 ELSE 0 END) AS hoje
FROM dbo.tb_log_integracao WHERE sistema_destino='VEDACIT' AND status_canhoto='SUCESSO' AND ISNULL(arquivado,0)=0;
SELECT COUNT(*) AS potencialmente_afetados, MIN(a.data_processamento_canhoto) AS inicio,
    MAX(a.data_processamento_canhoto) AS fim
FROM dbo.tb_log_integracao a
WHERE a.sistema_destino='VEDACIT' AND a.sftp_cliente='VEDACIT'
    AND a.status_canhoto='SUCESSO' AND ISNULL(a.arquivado,0)=0 AND a.tentativas_canhoto>1
    AND a.data_processamento_canhoto >= '2026-09-11T17:00:00' AND a.data_processamento_canhoto < '20260912'
    AND EXISTS (SELECT 1 FROM dbo.tb_log_integracao h WHERE h.arquivado=1
        AND h.sistema_destino=a.sistema_destino AND h.chave_nfe=a.chave_nfe AND h.chave_cte=a.chave_cte
        AND h.status_dados='SUCESSO' AND h.data_processamento_dados IS NOT NULL
        AND h.status_canhoto IN ('NAO_APLICAVEL','PENDENTE_FOTO'));
SELECT COUNT(*) AS ledger, SUM(CASE WHEN data_confiavel=0 THEN 1 ELSE 0 END) AS sem_data,
    SUM(CASE WHEN data_confiavel=1 AND primeira_confirmacao_em >= '20260911'
        AND primeira_confirmacao_em < '20260912' THEN 1 ELSE 0 END) AS datados_hoje
FROM dbo.tb_confirmacao_comprovante;
GO
