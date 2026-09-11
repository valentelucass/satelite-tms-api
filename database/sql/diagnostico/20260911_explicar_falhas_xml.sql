USE [SATELITE_TMS_AUDITORIA];
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Somente leitura: estado dos XMLs em falha no dia, sem expor chaves ou mensagens completas.
SELECT CASE WHEN mensagem_erro_dados LIKE '%ORIGEM_XML_AUTENTICACAO_EM_ESPERA%'
                 THEN 'ESPERA_AUTORIZACAO_ESL'
            WHEN mensagem_erro_dados LIKE '%ORIGEM_XML_HTTP_401%' OR mensagem_erro_dados LIKE '%401%'
                 THEN 'HTTP_401_ESL'
            ELSE 'OUTRO' END AS motivo,
       COUNT_BIG(*) AS registros, COUNT(DISTINCT chave_cte) AS ctes,
       MIN(data_processamento_dados) AS primeira_atualizacao,
       MAX(data_processamento_dados) AS ultima_atualizacao
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
  AND status_dados IN ('ERRO_DESTINO', 'ERRO_VALIDACAO')
  AND data_processamento_dados >= '2026-09-11' AND data_processamento_dados < '2026-09-12'
GROUP BY CASE WHEN mensagem_erro_dados LIKE '%ORIGEM_XML_AUTENTICACAO_EM_ESPERA%'
                   THEN 'ESPERA_AUTORIZACAO_ESL'
              WHEN mensagem_erro_dados LIKE '%ORIGEM_XML_HTTP_401%' OR mensagem_erro_dados LIKE '%401%'
                   THEN 'HTTP_401_ESL'
              ELSE 'OUTRO' END;
GO
SELECT COUNT(DISTINCT f.chave_cte) AS ctes_com_falha_e_confirmacao_datada
FROM dbo.tb_log_integracao f
WHERE f.sistema_destino = 'VEDACIT' AND (f.arquivado = 0 OR f.arquivado IS NULL)
  AND f.status_dados IN ('ERRO_DESTINO', 'ERRO_VALIDACAO')
  AND f.data_processamento_dados >= '2026-09-11' AND f.data_processamento_dados < '2026-09-12'
  AND EXISTS (
      SELECT 1 FROM dbo.tb_log_integracao s
      WHERE s.sistema_destino = 'VEDACIT' AND s.chave_cte = f.chave_cte
        AND s.status_dados IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
        AND s.data_processamento_dados IS NOT NULL
  );
GO
