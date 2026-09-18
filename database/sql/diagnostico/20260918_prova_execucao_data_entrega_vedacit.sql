USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
SET NOCOUNT ON;

-- Somente leitura: evidencia os comprovantes processados após o start humano
-- do worker que carregou o JAR EFF36F93... em 18/09/2026 às 17:41:55 BRT.
DECLARE @inicio_worker DATETIME2 = '2026-09-18T17:41:55.458';

SELECT
    'comprovantes_desde_start' AS consulta,
    l.id AS log_id,
    SUBSTRING(l.chave_nfe, 26, 9) AS numero_nfe,
    SUBSTRING(COALESCE(NULLIF(l.canhoto_chave_cte_efetiva, ''), l.chave_cte), 26, 9) AS numero_cte,
    l.occurrence_id AS ocorrencia_entrega_id,
    l.canhoto_origem,
    l.sftp_cliente,
    l.status_dados,
    l.status_canhoto,
    l.canhoto_classificacao_operacional,
    l.tentativas_canhoto,
    CONVERT(varchar(33), l.data_processamento_dados, 126) AS processamento_xml,
    CONVERT(varchar(33), l.data_processamento_canhoto, 126) AS aceite_comprovante_local,
    CONVERT(varchar(33), c.primeira_confirmacao_em, 126) AS primeira_confirmacao_em,
    c.data_confiavel AS confirmacao_datada,
    c.origem AS origem_confirmacao,
    c.log_origem_id AS log_confirmacao
FROM dbo.tb_log_integracao l
LEFT JOIN dbo.tb_confirmacao_comprovante c
    ON c.chave_nfe = l.chave_nfe
    AND c.chave_cte = COALESCE(NULLIF(l.canhoto_chave_cte_efetiva, ''), l.chave_cte)
WHERE l.sistema_destino = 'VEDACIT'
  AND l.data_processamento_canhoto >= @inicio_worker
ORDER BY l.data_processamento_canhoto ASC, l.id ASC;

SELECT
    'resumo_desde_start' AS consulta,
    COUNT_BIG(*) AS registros_com_canhoto_processado,
    SUM(CASE WHEN l.status_canhoto = 'SUCESSO' THEN 1 ELSE 0 END) AS comprovantes_aceitos,
    SUM(CASE WHEN l.status_canhoto = 'EM_PROCESSAMENTO' THEN 1 ELSE 0 END) AS comprovantes_ambiguous,
    SUM(CASE WHEN l.status_canhoto = 'ERRO_DESTINO' THEN 1 ELSE 0 END) AS comprovantes_com_erro,
    SUM(CASE WHEN l.status_canhoto = 'PENDENTE_FOTO' THEN 1 ELSE 0 END) AS comprovantes_pendentes,
    SUM(CASE WHEN l.occurrence_id IS NOT NULL THEN 1 ELSE 0 END) AS registros_com_ocorrencia_vinculada
FROM dbo.tb_log_integracao l
WHERE l.sistema_destino = 'VEDACIT'
  AND l.data_processamento_canhoto >= @inicio_worker;

SELECT
    'consultas_data_entrega_esl_desde_start' AS consulta,
    t.id AS telemetria_id,
    CONVERT(varchar(33), t.data_evento, 126) AS data_evento,
    t.destino,
    t.rota,
    t.template,
    t.status_http,
    t.tentativa,
    t.retry,
    t.fallback,
    t.cache_status,
    t.duracao_ms
FROM dbo.tb_esl_request_telemetria t
WHERE t.destino = 'VEDACIT'
  AND t.rota = 'VEDACIT_DELIVERY_OCCURRENCE'
  AND t.data_evento >= @inicio_worker
ORDER BY t.data_evento ASC, t.id ASC;
