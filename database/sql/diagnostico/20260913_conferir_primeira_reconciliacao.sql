USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Somente leitura. Horarios locais e cobertura da primeira madrugada real.
SELECT 'execucao' consulta, id, dia_referencia, inicio_em, fim_em, status,
       ultimo_log_id, limite_log_id, bloqueio_sftp, bloqueio_consulta_xml,
       bloqueio_consulta_pod, bloqueio_origem_xml, bloqueio_envio_xml,
       bloqueio_envio_pod, retomadas
FROM dbo.tb_reconciliacao_vedacit_execucao
WHERE dia_referencia = '20260913';

SELECT 'cobertura' consulta, COUNT_BIG(*) revisados,
       COUNT(DISTINCT i.log_id) logs_distintos,
       SUM(CASE WHEN i.arquivado = 1 THEN 1 ELSE 0 END) arquivados_conferidos,
       SUM(CASE WHEN i.arquivado = 1 AND l.arquivado = 0 THEN 1 ELSE 0 END) arquivados_reativados,
       SUM(CASE WHEN i.acao IN ('XML_RECUPERADO','COMPROVANTE_RECUPERADO') THEN 1 ELSE 0 END) recuperados,
       MIN(i.revisado_em) primeiro_item, MAX(i.revisado_em) ultimo_item,
       MIN(i.proxima_revisao_em) proxima_revisao
FROM dbo.tb_reconciliacao_vedacit_item i
JOIN dbo.tb_reconciliacao_vedacit_execucao e ON e.id = i.execucao_id
JOIN dbo.tb_log_integracao l ON l.id = i.log_id
WHERE e.dia_referencia = '20260913';

SELECT 'timeout' consulta, COUNT_BIG(*) timeouts_ativos,
       SUM(CASE WHEN i.log_id IS NOT NULL THEN 1 ELSE 0 END) revisados,
       SUM(CASE WHEN i.acao = 'ENVIO_RETIDO_AGUARDA_CONFIRMACAO_EXATA' THEN 1 ELSE 0 END) retidos
FROM dbo.tb_log_integracao l
LEFT JOIN dbo.tb_reconciliacao_vedacit_execucao e ON e.dia_referencia = '20260913'
LEFT JOIN dbo.tb_reconciliacao_vedacit_item i ON i.execucao_id = e.id AND i.log_id = l.id
WHERE l.sistema_destino = 'VEDACIT' AND (l.arquivado = 0 OR l.arquivado IS NULL)
  AND l.canhoto_classificacao_operacional = 'TIMEOUT_AMBIGUO';

-- Replica a elegibilidade vigente para detectar candidato nao revisado dentro do limite.
SELECT 'candidatos_sem_revisao' consulta, COUNT_BIG(*) registros
FROM dbo.tb_log_integracao l
JOIN dbo.tb_reconciliacao_vedacit_execucao e ON e.dia_referencia = '20260913'
LEFT JOIN dbo.tb_confirmacao_comprovante c ON c.chave_nfe = l.chave_nfe
  AND c.chave_cte = COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte)
WHERE l.sistema_destino = 'VEDACIT' AND l.id <= e.limite_log_id
  AND (l.status LIKE 'ERRO%' OR l.status_dados LIKE 'ERRO%' OR l.status_canhoto LIKE 'ERRO%'
    OR l.status IN ('PARCIAL','PENDENTE_FOTO')
    OR l.status_dados IN ('ERRO_DESTINO','PENDENTE_ORIGEM')
    OR (l.status_dados = 'SUCESSO' AND l.data_processamento_dados IS NULL)
    OR l.status_canhoto IN ('ERRO_DESTINO','PENDENTE_FOTO')
    OR l.canhoto_classificacao_operacional IN ('PENDENTE_ENVIO','PENDENTE_TECNICO',
        'BLOQUEADO_ORIGEM','BLOQUEADO_DESTINO','TIMEOUT_AMBIGUO')
    OR (l.status_canhoto = 'SUCESSO' AND (c.data_confiavel = 0 OR c.log_origem_id IS NULL)))
  AND NOT EXISTS (SELECT 1 FROM dbo.tb_reconciliacao_vedacit_item i
                  WHERE i.execucao_id = e.id AND i.log_id = l.id);
