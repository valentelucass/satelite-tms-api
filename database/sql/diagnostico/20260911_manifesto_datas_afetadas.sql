USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
SELECT a.id, CONVERT(VARCHAR(33), a.data_processamento_canhoto,126) AS data_ultimo_retorno FROM dbo.tb_log_integracao a
WHERE a.sistema_destino='VEDACIT' AND a.sftp_cliente='VEDACIT'
    AND a.status_canhoto='SUCESSO' AND ISNULL(a.arquivado,0)=0 AND a.tentativas_canhoto>1
    AND a.data_processamento_canhoto >= '2026-09-11T17:00:00' AND a.data_processamento_canhoto < '20260912'
    AND EXISTS (SELECT 1 FROM dbo.tb_log_integracao h WHERE h.arquivado=1
        AND h.sistema_destino=a.sistema_destino AND h.chave_nfe=a.chave_nfe AND h.chave_cte=a.chave_cte
        AND h.status_dados='SUCESSO' AND h.data_processamento_dados IS NOT NULL
        AND h.status_canhoto IN ('NAO_APLICAVEL','PENDENTE_FOTO'));