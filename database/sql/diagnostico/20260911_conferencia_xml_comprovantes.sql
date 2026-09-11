USE [SATELITE_TMS_AUDITORIA];
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Somente leitura. Recorte fixo da investigação, sem documentos/payloads na saída.
SELECT 'relogio' AS consulta, DB_NAME() AS database_atual, SYSDATETIME() AS agora;
GO
SELECT 'cursor_xml' AS consulta, sistema_destino, cursor_next_id, data_atualizacao
FROM dbo.tb_controle_cursor WHERE sistema_destino = 'VEDACIT_XML';
GO
WITH confirmacoes AS (
    SELECT 'XML' AS etapa, chave_cte AS documento, MIN(data_processamento_dados) AS primeira_data
    FROM dbo.tb_log_integracao
    WHERE sistema_destino = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
      AND status_dados IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') AND data_processamento_dados IS NOT NULL
    GROUP BY chave_cte
    UNION ALL
    SELECT 'COMPROVANTE', CONCAT(chave_nfe, ':', COALESCE(NULLIF(canhoto_chave_cte_efetiva, ''), chave_cte)),
           MIN(data_processamento_canhoto)
    FROM dbo.tb_log_integracao
    WHERE sistema_destino = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
      AND status_canhoto IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') AND data_processamento_canhoto IS NOT NULL
    GROUP BY chave_nfe, COALESCE(NULLIF(canhoto_chave_cte_efetiva, ''), chave_cte)
)
SELECT 'confirmacoes_independentes' AS consulta, etapa, CAST(primeira_data AS DATE) AS dia,
       COUNT_BIG(*) AS documentos, MIN(primeira_data) AS primeira, MAX(primeira_data) AS ultima
FROM confirmacoes WHERE primeira_data >= '2026-09-10' AND primeira_data < '2026-09-12'
GROUP BY etapa, CAST(primeira_data AS DATE);
GO
SELECT 'erros_xml_ativos' AS consulta, id, occurrence_id, cursor_next_id, status_dados,
       tentativas_dados, data_processamento_dados, data_processamento,
       CASE WHEN mensagem_erro_dados LIKE '%401%' THEN 'HTTP_401_DOWNLOAD_ESL'
            WHEN mensagem_erro_dados LIKE '%timed out%' THEN 'TIMEOUT'
            ELSE 'OUTRO' END AS motivo
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
  AND status_dados IN ('ERRO_DESTINO', 'ERRO_VALIDACAO')
ORDER BY id;
GO
SELECT 'classificacoes_sftp' AS consulta, status_dados, status_canhoto,
       canhoto_classificacao_operacional AS classificacao, COUNT_BIG(*) AS registros
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT' AND sftp_cliente = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
GROUP BY status_dados, status_canhoto, canhoto_classificacao_operacional;
GO
WITH motivos AS (
    SELECT canhoto_classificacao_operacional AS classificacao,
        CASE WHEN canhoto_classificacao_operacional = 'BLOQUEADO_DESTINO' THEN 'RECUSA_DESTINO'
             WHEN mensagem_erro_canhoto LIKE '%timed out%' THEN 'READ_TIMEOUT'
             WHEN mensagem_erro_canhoto LIKE '%XML%' OR mensagem_erro_canhoto LIKE '%dados%' THEN 'DEPENDENCIA_XML_DADOS'
             WHEN mensagem_erro_canhoto LIKE '%nome%' OR mensagem_erro_canhoto LIKE '%chaves%' THEN 'NOME_CHAVES_INVALIDOS'
             WHEN mensagem_erro_canhoto LIKE N'%instável%' OR mensagem_erro_canhoto LIKE N'%estável%'
                  OR mensagem_erro_canhoto LIKE '%estabilidade%' THEN 'ARQUIVO_INSTAVEL'
             ELSE 'OUTRO' END AS motivo,
        data_processamento_canhoto
    FROM dbo.tb_log_integracao
    WHERE sistema_destino = 'VEDACIT' AND sftp_cliente = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
      AND canhoto_classificacao_operacional <> 'SUCESSO'
)
SELECT 'motivos_sftp' AS consulta, classificacao, motivo, COUNT_BIG(*) AS registros,
       MIN(data_processamento_canhoto) AS primeira_data, MAX(data_processamento_canhoto) AS ultima_data
FROM motivos GROUP BY classificacao, motivo;
GO
SELECT 'falhas_canhoto_hoje' AS consulta, id, status_canhoto, canhoto_classificacao_operacional,
       data_processamento_canhoto, tentativas_canhoto,
       CASE WHEN mensagem_erro_canhoto LIKE '%timed out%' THEN 'READ_TIMEOUT' ELSE 'OUTRO' END AS motivo
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
  AND status_canhoto IN ('ERRO_DESTINO', 'ERRO_VALIDACAO')
  AND data_processamento_canhoto >= '2026-09-11' AND data_processamento_canhoto < '2026-09-12'
ORDER BY data_processamento_canhoto;
GO
SELECT 'telemetria_esl' AS consulta, CAST(data_evento AS DATE) AS dia, rota, status_http,
       COUNT_BIG(*) AS chamadas, MIN(data_evento) AS primeira, MAX(data_evento) AS ultima
FROM dbo.tb_esl_request_telemetria
WHERE destino = 'VEDACIT' AND data_evento >= '2026-09-10T23:13:06' AND data_evento < '2026-09-12'
GROUP BY CAST(data_evento AS DATE), rota, status_http;
GO
SELECT 'ciclos_hoje' AS consulta, inicio_em, fim_em, conexao, status_ciclo, arquivos_validos,
       arquivos_rejeitados, selecionados, enviados, pendentes, saldo, bloqueios, timeouts_ambiguos, duracao_ms
FROM dbo.tb_work_sftp_cliente_execucao
WHERE sftp_cliente = 'VEDACIT' AND fim_em >= '2026-09-11' AND fim_em < '2026-09-12'
ORDER BY fim_em;
GO
-- Decomposição dos saldos: mesma identidade documental, com prioridade da confirmação datada.
WITH etapas AS (
    SELECT id, sistema_destino, sftp_cliente, data_processamento, 'DADOS' AS etapa,
           status_dados AS situacao, data_processamento_dados AS data_etapa,
           CAST(NULL AS VARCHAR(40)) AS bloqueio,
           CASE WHEN sistema_destino = 'VEDACIT' AND NULLIF(chave_cte, '') IS NOT NULL THEN CONCAT('CTE:', chave_cte)
                WHEN sistema_destino <> 'VEDACIT' AND occurrence_id IS NOT NULL THEN CONCAT('OC:', occurrence_id)
                ELSE CONCAT('LOG:', id) END AS documento
    FROM dbo.tb_log_integracao
    WHERE sistema_destino IN ('VEDACIT', 'PPG', 'SELIA') AND (arquivado = 0 OR arquivado IS NULL)
    UNION ALL
    SELECT id, sistema_destino, sftp_cliente, data_processamento, 'COMPROVANTE', status_canhoto,
           data_processamento_canhoto, canhoto_classificacao_operacional,
           CASE WHEN sistema_destino = 'VEDACIT' AND NULLIF(chave_nfe, '') IS NOT NULL
                     AND NULLIF(COALESCE(NULLIF(canhoto_chave_cte_efetiva, ''), chave_cte), '') IS NOT NULL
                THEN CONCAT('POD:', chave_nfe, ':', COALESCE(NULLIF(canhoto_chave_cte_efetiva, ''), chave_cte))
                WHEN sistema_destino <> 'VEDACIT' AND occurrence_id IS NOT NULL THEN CONCAT('OC:', occurrence_id)
                ELSE CONCAT('LOG:', id) END
    FROM dbo.tb_log_integracao
    WHERE sistema_destino IN ('VEDACIT', 'PPG', 'SELIA') AND (arquivado = 0 OR arquivado IS NULL)
), ordenadas AS (
    SELECT *, ROW_NUMBER() OVER (PARTITION BY sistema_destino, etapa, documento
        ORDER BY CASE WHEN situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') AND data_etapa IS NOT NULL THEN 0 ELSE 1 END,
                 CASE WHEN situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') AND data_etapa IS NOT NULL THEN data_etapa END,
                 data_processamento DESC, id DESC) AS ordem
    FROM etapas
)
SELECT 'decomposicao_etapas' AS consulta, sistema_destino, etapa, situacao, bloqueio,
       CASE WHEN data_etapa IS NULL THEN 'SEM_DATA' ELSE 'COM_DATA' END AS data_estado,
       CASE WHEN ordem = 1 THEN 'CONTADO' ELSE 'COPIA_SUPERADA' END AS uso_indicador,
       COUNT_BIG(*) AS registros
FROM ordenadas
GROUP BY sistema_destino, etapa, situacao, bloqueio,
         CASE WHEN data_etapa IS NULL THEN 'SEM_DATA' ELSE 'COM_DATA' END,
         CASE WHEN ordem = 1 THEN 'CONTADO' ELSE 'COPIA_SUPERADA' END;
GO
SELECT 'pendencias_documentais_sftp' AS consulta,
       SUM(CASE WHEN canhoto_classificacao_operacional = 'BLOQUEADO_ORIGEM' AND status_dados = 'PENDENTE_ORIGEM' THEN 1 ELSE 0 END) AS linhas_sem_xml,
       COUNT(DISTINCT CASE WHEN canhoto_classificacao_operacional = 'BLOQUEADO_ORIGEM' AND status_dados = 'PENDENTE_ORIGEM' THEN chave_cte END) AS ctes_sem_xml,
       COUNT(DISTINCT CASE WHEN canhoto_classificacao_operacional = 'BLOQUEADO_ORIGEM' AND status_dados = 'PENDENTE_ORIGEM'
                          THEN CONCAT(chave_nfe, ':', COALESCE(NULLIF(canhoto_chave_cte_efetiva, ''), chave_cte)) END) AS pares_sem_xml
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT' AND sftp_cliente = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL);
