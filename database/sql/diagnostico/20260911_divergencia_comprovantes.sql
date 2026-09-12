USE [SATELITE_TMS_AUDITORIA];
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Diagnostico somente leitura. @anteriores/@atuais: arrays JSON de chaves NF-e.
-- @inicio/@fim: janela do dia; @corte: instante da consulta.
WITH anteriores AS (
    SELECT CAST(value AS VARCHAR(44)) AS nfe FROM OPENJSON(@anteriores)
), atuais AS (
    SELECT CAST(value AS VARCHAR(44)) AS nfe FROM OPENJSON(@atuais)
), base AS (
    SELECT l.id, l.chave_nfe, l.chave_cte,
        COALESCE(NULLIF(l.canhoto_chave_cte_efetiva, ''), l.chave_cte) AS cte_efetivo,
        UPPER(TRIM(COALESCE(l.status_canhoto, ''))) AS situacao,
        l.status_dados, l.data_processamento_canhoto AS data_etapa,
        l.data_processamento_dados AS data_xml, l.data_processamento,
        l.canhoto_classificacao_operacional AS bloqueio, l.canhoto_origem,
        l.sftp_cliente, l.tentativas_canhoto, l.arquivado,
        l.canhoto_reconciliacao_tipo,
        CASE WHEN l.mensagem_erro_canhoto LIKE '%timed out%' THEN 'TIMEOUT'
             WHEN l.mensagem_erro_canhoto LIKE '%XML%' OR l.mensagem_erro_canhoto LIKE '%dados%' THEN 'DEPENDENCIA_XML'
             WHEN l.mensagem_erro_canhoto LIKE '%indispon%' THEN 'ARQUIVO_INDISPONIVEL'
             WHEN l.mensagem_erro_canhoto IS NOT NULL THEN 'OUTRO' ELSE NULL END AS motivo
    FROM dbo.tb_log_integracao l
    WHERE l.sistema_destino = 'VEDACIT'
), normalizadas AS (
    SELECT *, CASE WHEN NULLIF(chave_nfe, '') IS NOT NULL AND NULLIF(cte_efetivo, '') IS NOT NULL
                  THEN CONCAT('POD:', chave_nfe, ':', cte_efetivo)
                  ELSE CONCAT('LOG:', CAST(id AS VARCHAR(30))) END AS documento
    FROM base
), historico AS (
    SELECT documento, MIN(data_etapa) AS primeira_historica,
        MIN(CASE WHEN arquivado = 1 THEN data_etapa END) AS primeira_arquivada
    FROM normalizadas
    WHERE situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') AND data_etapa IS NOT NULL
    GROUP BY documento
), ordenadas AS (
    SELECT *, ROW_NUMBER() OVER (PARTITION BY documento
        ORDER BY CASE WHEN situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') AND data_etapa IS NOT NULL THEN 0 ELSE 1 END,
        CASE WHEN situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') AND data_etapa IS NOT NULL THEN data_etapa END ASC,
        data_processamento DESC, id DESC) AS ordem
    FROM normalizadas WHERE arquivado = 0 OR arquivado IS NULL
), documentos AS (
    SELECT o.*, h.primeira_historica, h.primeira_arquivada,
        CASE WHEN a.nfe IS NOT NULL AND t.nfe IS NULL THEN 'SAIU_DAS_798'
             WHEN a.nfe IS NOT NULL AND t.nfe IS NOT NULL THEN 'PERMANECE_NAS_505'
             WHEN a.nfe IS NULL AND t.nfe IS NOT NULL THEN 'ENTROU_NAS_505'
             ELSE 'FORA_DAS_PLANILHAS' END AS grupo,
        CASE WHEN o.situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') AND o.data_etapa IS NOT NULL THEN 'CONFIRMADO'
             WHEN o.situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') THEN 'SUCESSO_SEM_DATA'
             WHEN o.bloqueio IS NOT NULL THEN o.bloqueio ELSE o.situacao END AS classe
    FROM ordenadas o LEFT JOIN historico h ON h.documento = o.documento
    LEFT JOIN anteriores a ON a.nfe = o.chave_nfe LEFT JOIN atuais t ON t.nfe = o.chave_nfe
    WHERE o.ordem = 1
)
-- O runner executa esta mesma CTE com cada SELECT abaixo, sem criar objetos SQL.
/* CONSULTA */
SELECT grupo, classe, COUNT_BIG(*) AS pares, COUNT(DISTINCT chave_nfe) AS nfes,
    SUM(CASE WHEN classe = 'CONFIRMADO' AND data_etapa >= @inicio AND data_etapa < @fim AND data_etapa <= @corte THEN 1 ELSE 0 END) AS confirmados_hoje,
    MIN(CASE WHEN classe = 'CONFIRMADO' THEN data_etapa END) AS primeira,
    MAX(CASE WHEN classe = 'CONFIRMADO' THEN data_etapa END) AS ultima
FROM documentos GROUP BY grupo, classe ORDER BY grupo, classe;
/* CONSULTA */
SELECT grupo, DATEPART(HOUR, data_etapa) AS hora, COUNT_BIG(*) AS pares,
    COUNT(DISTINCT chave_nfe) AS nfes,
    SUM(CASE WHEN primeira_historica < @inicio THEN 1 ELSE 0 END) AS sucesso_historico_anterior,
    MIN(data_etapa) AS primeira, MAX(data_etapa) AS ultima
FROM documentos
WHERE classe = 'CONFIRMADO' AND data_etapa >= @inicio AND data_etapa < @fim AND data_etapa <= @corte
GROUP BY grupo, DATEPART(HOUR, data_etapa) ORDER BY hora, grupo;
/* CONSULTA */
SELECT SUBSTRING(chave_nfe, 7, 14) AS emitente_nfe, SUBSTRING(chave_nfe, 3, 4) AS mes_nfe,
    grupo, COUNT_BIG(*) AS pares, COUNT(DISTINCT chave_nfe) AS nfes
FROM documentos
WHERE classe = 'CONFIRMADO' AND data_etapa >= @inicio AND data_etapa < @fim AND data_etapa <= @corte
GROUP BY SUBSTRING(chave_nfe, 7, 14), SUBSTRING(chave_nfe, 3, 4), grupo
ORDER BY emitente_nfe, mes_nfe, grupo;
/* CONSULTA */
SELECT grupo, chave_nfe, id, chave_cte, cte_efetivo, classe, situacao, status_dados,
    data_etapa, data_xml, data_processamento, primeira_historica, primeira_arquivada,
    canhoto_origem, sftp_cliente, tentativas_canhoto, canhoto_reconciliacao_tipo, motivo
FROM documentos
WHERE grupo <> 'FORA_DAS_PLANILHAS'
   OR (classe = 'CONFIRMADO' AND data_etapa >= @inicio AND data_etapa < @fim AND data_etapa <= @corte)
ORDER BY grupo, chave_nfe, id;
/* CONSULTA */
SELECT 'ANTERIORES' AS lista, COUNT_BIG(*) AS nfes FROM anteriores
UNION ALL SELECT 'ATUAIS', COUNT_BIG(*) FROM atuais
UNION ALL SELECT 'INTERSECAO', COUNT_BIG(*) FROM anteriores a JOIN atuais t ON a.nfe=t.nfe
UNION ALL SELECT 'ATUAIS_SEM_AUDITORIA', COUNT_BIG(*) FROM atuais t WHERE NOT EXISTS (SELECT 1 FROM base b WHERE b.chave_nfe=t.nfe)
UNION ALL SELECT 'SAIRAM_SEM_CONFIRMACAO_LOCAL', COUNT_BIG(*) FROM anteriores a
WHERE NOT EXISTS (SELECT 1 FROM atuais t WHERE t.nfe=a.nfe)
  AND NOT EXISTS (SELECT 1 FROM documentos d WHERE d.chave_nfe=a.nfe AND d.classe='CONFIRMADO');
/* CONSULTA */
SELECT d.grupo, h.situacao AS canhoto_legado, h.status_dados AS xml_legado,
    COUNT_BIG(*) AS pares_com_legado, MIN(h.data_xml) AS primeiro_xml_legado,
    MAX(h.data_etapa) AS ultima_data_canhoto_legado
FROM documentos d CROSS APPLY (
    SELECT TOP (1) b.* FROM base b
    WHERE b.chave_nfe=d.chave_nfe AND b.chave_cte=d.chave_cte AND b.arquivado=1
      AND b.status_dados='SUCESSO' AND b.data_xml IS NOT NULL
    ORDER BY b.data_processamento DESC, b.id DESC
) h
WHERE d.classe='CONFIRMADO' AND d.data_etapa >= @inicio AND d.data_etapa < @fim AND d.data_etapa<=@corte
GROUP BY d.grupo, h.situacao, h.status_dados;
/* CONSULTA */
SELECT id, arquivado, situacao, status_dados, data_etapa, data_xml, data_processamento,
    tentativas_canhoto, sftp_cliente, canhoto_reconciliacao_tipo
FROM base WHERE chave_nfe IN (SELECT chave_nfe FROM base WHERE id IN (8981,9411,9012))
ORDER BY chave_nfe, id;
/* CONSULTA */
SELECT COUNT_BIG(*) AS sucessos_ainda_expostos_a_reconciliacao
FROM base a WHERE (a.arquivado=0 OR a.arquivado IS NULL) AND a.situacao='SUCESSO'
  AND a.status_dados='SUCESSO' AND a.data_xml IS NULL AND a.sftp_cliente='VEDACIT'
  AND EXISTS (SELECT 1 FROM base h WHERE h.chave_nfe=a.chave_nfe AND h.chave_cte=a.chave_cte
    AND h.arquivado=1 AND h.status_dados='SUCESSO' AND h.data_xml IS NOT NULL AND h.situacao<>'SUCESSO');
/* CONSULTA */
SELECT data_etapa AS data_do_965, grupo
FROM documentos WHERE classe='CONFIRMADO' AND data_etapa>=@inicio AND data_etapa<@fim AND data_etapa<=@corte
ORDER BY data_etapa, id OFFSET 964 ROWS FETCH NEXT 1 ROWS ONLY;
/* CONSULTA */
SELECT r.grupo, COUNT_BIG(*) AS pares_no_965, COUNT(DISTINCT r.chave_nfe) AS nfes,
    SUM(CASE WHEN r.tentativas_canhoto>1 THEN 1 ELSE 0 END) AS com_tentativa_anterior
FROM (SELECT TOP (965) * FROM documentos
      WHERE classe='CONFIRMADO' AND data_etapa>=@inicio AND data_etapa<@fim AND data_etapa<=@corte
      ORDER BY data_etapa,id) r
GROUP BY r.grupo;
/* CONSULTA */
SELECT a.id, a.data_etapa, a.data_xml, a.tentativas_canhoto,
    h.id AS id_legado, h.situacao AS situacao_legado, h.data_xml AS xml_legado
FROM base a CROSS APPLY (SELECT TOP (1) b.* FROM base b
    WHERE b.chave_nfe=a.chave_nfe AND b.chave_cte=a.chave_cte
      AND b.arquivado=1 AND b.status_dados='SUCESSO' AND b.data_xml IS NOT NULL AND b.situacao<>'SUCESSO'
    ORDER BY b.data_processamento DESC,b.id DESC) h
WHERE (a.arquivado=0 OR a.arquivado IS NULL) AND a.situacao='SUCESSO'
  AND a.status_dados='SUCESSO' AND a.data_xml IS NULL AND a.sftp_cliente='VEDACIT';
