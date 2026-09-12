USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Somente leitura. Executar com scripts/executar_sql_auditoria.ps1 -Rollback.
SELECT 'schema' AS tipo, DB_NAME() AS banco, SYSDATETIME() AS corte,
    OBJECT_ID('dbo.tb_confirmacao_comprovante','U') AS tabela_confirmacoes,
    OBJECT_ID('dbo.tb_ajuste_confirmacao_comprovante','U') AS tabela_ajustes,
    (SELECT COUNT(*) FROM sys.triggers WHERE object_id=OBJECT_ID('dbo.tr_log_preserva_confirmacao_comprovante')
        AND is_disabled=0) AS trigger_ativo;
SELECT 'totais' AS tipo, COUNT_BIG(*) AS pares_preservados,
    SUM(CASE WHEN data_confiavel=0 THEN 1 ELSE 0 END) AS primeira_data_incerta,
    SUM(CASE WHEN data_confiavel=1 AND primeira_confirmacao_em>='20260911'
        AND primeira_confirmacao_em<'20260912' THEN 1 ELSE 0 END) AS confirmacoes_dataveis_11_setembro,
    SUM(CASE WHEN data_confiavel=1 AND primeira_confirmacao_em IS NULL
        OR data_confiavel=0 AND primeira_confirmacao_em IS NOT NULL THEN 1 ELSE 0 END) AS datas_inconsistentes
FROM dbo.tb_confirmacao_comprovante;
SELECT 'manifesto' AS tipo, COUNT_BIG(*) AS linhas,
    SUM(CASE WHEN l.id IS NULL OR l.status_canhoto<>'SUCESSO'
        OR l.data_processamento_canhoto<>a.ultimo_retorno
        OR l.chave_nfe<>a.chave_nfe
        OR COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte)<>a.chave_cte THEN 1 ELSE 0 END) AS auditorias_divergentes,
    SUM(CASE WHEN c.chave_nfe IS NULL OR c.data_confiavel<>0 OR c.primeira_confirmacao_em IS NOT NULL
        THEN 1 ELSE 0 END) AS qualificacoes_divergentes
FROM dbo.tb_ajuste_confirmacao_comprovante a
LEFT JOIN dbo.tb_log_integracao l ON l.id=a.log_id
LEFT JOIN dbo.tb_confirmacao_comprovante c ON c.chave_nfe=a.chave_nfe AND c.chave_cte=a.chave_cte
WHERE a.incidente='CONCILIACAO_XML_20260911';
SELECT 'aceites_sem_registro_permanente' AS tipo, COUNT_BIG(*) AS divergencias
FROM dbo.tb_log_integracao l
WHERE l.sistema_destino='VEDACIT' AND l.status_canhoto='SUCESSO'
    AND LEN(l.chave_nfe)=44 AND LEN(COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte))=44
    AND NOT EXISTS (SELECT 1 FROM dbo.tb_confirmacao_comprovante c WHERE c.chave_nfe=l.chave_nfe
        AND c.chave_cte=COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte));
SELECT 'primeira_data_deslocada' AS tipo, COUNT_BIG(*) AS divergencias
FROM dbo.tb_confirmacao_comprovante c
WHERE c.data_confiavel=1 AND EXISTS (SELECT 1 FROM dbo.tb_log_integracao l
    WHERE l.sistema_destino='VEDACIT' AND l.status_canhoto='SUCESSO' AND l.chave_nfe=c.chave_nfe
      AND COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte)=c.chave_cte
      AND l.data_processamento_canhoto<c.primeira_confirmacao_em);
SELECT 'trigger_definicao' AS tipo, definition
FROM sys.sql_modules WHERE object_id=OBJECT_ID('dbo.tr_log_preserva_confirmacao_comprovante');
GO
