USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Somente leitura: pendências residuais, proteção e alcance da recuperação XML.
SELECT 'residuais' consulta, l.id, l.occurrence_id, l.status_dados, l.status_canhoto,
       l.canhoto_classificacao_operacional classificacao, l.tentativas_dados, l.tentativas_canhoto,
       SUBSTRING(l.chave_nfe,26,9) numero_nf, SUBSTRING(l.chave_cte,26,9) numero_cte,
       RIGHT(l.chave_cte,8) cte_final, l.data_processamento_dados, l.data_processamento_canhoto,
       c.primeira_confirmacao_em confirmacao_permanente,
       CASE WHEN c.chave_nfe IS NOT NULL THEN 1 ELSE 0 END tem_confirmacao,
       LEFT(REPLACE(REPLACE(COALESCE(l.mensagem_erro_canhoto,''),COALESCE(NULLIF(l.chave_nfe,''),'[SEM_NFE]'),'[NFE]'),COALESCE(NULLIF(l.chave_cte,''),'[SEM_CTE]'),'[CTE]'),600) mensagem_pod
FROM dbo.tb_log_integracao l LEFT JOIN dbo.tb_confirmacao_comprovante c
 ON c.chave_nfe=l.chave_nfe AND c.chave_cte=COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte)
WHERE l.sistema_destino='VEDACIT' AND (l.arquivado=0 OR l.arquivado IS NULL)
 AND (l.canhoto_classificacao_operacional IN ('PENDENTE_ENVIO','BLOQUEADO_DESTINO')
      OR (l.status_dados='PENDENTE_ORIGEM' AND c.chave_nfe IS NOT NULL));
SELECT 'historico_nf_pendente' consulta, l.id, l.arquivado, l.status_dados, l.status_canhoto,
       l.canhoto_classificacao_operacional classificacao,
       SUBSTRING(l.chave_cte,26,9) numero_cte, RIGHT(l.chave_cte,8) cte_final,
       SUBSTRING(l.canhoto_chave_cte_efetiva,26,9) cte_efetivo,
       l.data_processamento_dados, l.data_processamento_canhoto
FROM dbo.tb_log_integracao l
WHERE l.sistema_destino='VEDACIT'
 AND l.chave_nfe=(SELECT chave_nfe FROM dbo.tb_log_integracao WHERE id=7579);
SELECT 'xml_sem_data_historico' consulta, l.id, l.arquivado, l.status_dados,
       SUBSTRING(l.chave_cte,26,9) numero_cte, l.data_processamento_dados,
       CASE WHEN l.chave_cte IS NULL THEN 0 ELSE 1 END tem_chave
FROM dbo.tb_log_integracao l
WHERE l.sistema_destino='VEDACIT' AND (l.arquivado=0 OR l.arquivado IS NULL)
 AND l.status_dados='SUCESSO' AND l.data_processamento_dados IS NULL;
SELECT 'retencao_xml_automatica' consulta, COUNT_BIG(*) registros
FROM dbo.tb_log_integracao l
WHERE l.sistema_destino='VEDACIT' AND (l.arquivado=0 OR l.arquivado IS NULL)
 AND l.status_dados IN ('ERRO_DESTINO','PENDENTE_ORIGEM') AND l.chave_cte IS NOT NULL AND l.chave_nfe IS NOT NULL
 AND l.data_processamento_dados<=DATEADD(MINUTE,-30,SYSDATETIME())
 AND (l.mensagem_erro_dados LIKE 'ORIGEM_XML_%' OR l.mensagem_erro_dados LIKE 'SOAP_ANTERIOR_EM_ANDAMENTO%'
      OR (l.mensagem_erro_dados LIKE '%401%' AND l.mensagem_erro_dados LIKE '%RodogarciaClient#buscarXmlCte%'))
 AND NOT EXISTS (SELECT 1 FROM dbo.tb_log_integracao p WHERE p.sistema_destino='VEDACIT'
                 AND p.chave_cte=l.chave_cte AND p.status_dados='SUCESSO');
SELECT 'seis_xml_anteriores' consulta, l.id, l.arquivado, l.status_dados, l.tentativas_dados,
       l.data_processamento_dados, l.data_processamento, l.status_canhoto,
       MIN(a.data_processamento_dados) primeira_confirmacao_cte
FROM dbo.tb_log_integracao l LEFT JOIN dbo.tb_log_integracao a
 ON a.sistema_destino='VEDACIT' AND a.chave_cte=l.chave_cte AND a.status_dados='SUCESSO'
WHERE l.id BETWEEN 10561 AND 10566
GROUP BY l.id,l.arquivado,l.status_dados,l.tentativas_dados,l.data_processamento_dados,l.data_processamento,l.status_canhoto;
SELECT 'ultimos_aceites' consulta, 'XML' etapa, MAX(data_processamento_dados) ultima
FROM dbo.tb_log_integracao WHERE sistema_destino='VEDACIT' AND status_dados='SUCESSO'
UNION ALL SELECT 'ultimos_aceites','COMPROVANTE',MAX(data_processamento_canhoto)
FROM dbo.tb_log_integracao WHERE sistema_destino='VEDACIT' AND status_canhoto='SUCESSO';
SELECT 'integridade_confirmacoes' consulta, COUNT_BIG(*) registros_divergentes
FROM dbo.tb_log_integracao l
WHERE l.sistema_destino='VEDACIT' AND l.status_canhoto='SUCESSO'
 AND LEN(l.chave_nfe)=44 AND LEN(COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte))=44
 AND NOT EXISTS (SELECT 1 FROM dbo.tb_confirmacao_comprovante c WHERE c.chave_nfe=l.chave_nfe
                 AND c.chave_cte=COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte));
SELECT 'timeouts_com_sucesso_historico' consulta, COUNT_BIG(*) registros
FROM dbo.tb_log_integracao l
WHERE l.sistema_destino='VEDACIT' AND (l.arquivado=0 OR l.arquivado IS NULL)
 AND l.canhoto_classificacao_operacional='TIMEOUT_AMBIGUO'
 AND EXISTS (SELECT 1 FROM dbo.tb_log_integracao a WHERE a.sistema_destino='VEDACIT'
      AND a.chave_nfe=l.chave_nfe AND COALESCE(NULLIF(a.canhoto_chave_cte_efetiva,''),a.chave_cte)=COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte)
      AND a.status_canhoto='SUCESSO');
SELECT 'arquivamento' consulta, arquivado_motivo, arquivado_em,
       COUNT_BIG(*) registros, MIN(id) primeiro_id, MAX(id) ultimo_id,
       SUM(CASE WHEN status_dados='ERRO_DESTINO' THEN 1 ELSE 0 END) erros_xml,
       SUM(CASE WHEN status_dados='SUCESSO' THEN 1 ELSE 0 END) sucessos_xml
FROM dbo.tb_log_integracao WHERE sistema_destino='VEDACIT' AND arquivado=1
GROUP BY arquivado_motivo, arquivado_em;
SELECT 'historicos_sucesso_sem_identidade' consulta, COUNT_BIG(*) registros,
       SUM(CASE WHEN arquivado=1 THEN 1 ELSE 0 END) arquivados
FROM dbo.tb_log_integracao WHERE sistema_destino='VEDACIT' AND status_canhoto='SUCESSO'
 AND (LEN(COALESCE(chave_nfe,''))<>44 OR LEN(COALESCE(NULLIF(canhoto_chave_cte_efetiva,''),chave_cte,''))<>44);
SELECT 'historicos_xml_correlatos' consulta, a.id, a.arquivado, a.arquivado_em, a.arquivado_motivo,
       a.status_dados, a.data_processamento_dados, a.tentativas_dados,
       CASE WHEN NULLIF(a.response_payload,'') IS NOT NULL THEN 1 ELSE 0 END tem_resposta,
       CASE WHEN NULLIF(a.request_payload,'') IS NOT NULL THEN 1 ELSE 0 END tem_requisicao,
       COUNT(DISTINCT l.id) logs_ativos_correlatos
FROM dbo.tb_log_integracao a JOIN dbo.tb_log_integracao l
 ON l.sistema_destino='VEDACIT' AND l.chave_cte=a.chave_cte AND (l.arquivado=0 OR l.arquivado IS NULL)
WHERE a.sistema_destino='VEDACIT' AND a.arquivado=1
 AND (l.status_dados='PENDENTE_ORIGEM' OR (l.status_dados='SUCESSO' AND l.data_processamento_dados IS NULL))
GROUP BY a.id,a.arquivado,a.arquivado_em,a.arquivado_motivo,a.status_dados,a.data_processamento_dados,
         a.tentativas_dados,CASE WHEN NULLIF(a.response_payload,'') IS NOT NULL THEN 1 ELSE 0 END,
         CASE WHEN NULLIF(a.request_payload,'') IS NOT NULL THEN 1 ELSE 0 END;
SELECT 'erros_xml_arquivados_recentes' consulta, COUNT_BIG(*) registros,
       COUNT(DISTINCT l.chave_cte) ctes,
       COUNT(DISTINCT CASE WHEN sucesso.chave_cte IS NULL THEN l.chave_cte END) ctes_sem_sucesso_historico
FROM dbo.tb_log_integracao l
LEFT JOIN (SELECT DISTINCT chave_cte FROM dbo.tb_log_integracao
           WHERE sistema_destino='VEDACIT' AND status_dados='SUCESSO') sucesso
  ON sucesso.chave_cte=l.chave_cte
WHERE l.sistema_destino='VEDACIT' AND l.arquivado=1 AND l.arquivado_em>='2026-09-11'
 AND l.status_dados='ERRO_DESTINO';
