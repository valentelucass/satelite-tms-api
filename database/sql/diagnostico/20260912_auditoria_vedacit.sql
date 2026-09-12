USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Somente SELECT. Executar com scripts/executar_sql_auditoria.ps1 -Rollback.
-- Identidades fiscais completas e payloads ficam fora da saída.
SELECT 'relogio' consulta, DB_NAME() database_atual, SYSDATETIME() agora;
SELECT 'cursor' consulta, sistema_destino, cursor_next_id, data_atualizacao
FROM dbo.tb_controle_cursor WHERE sistema_destino = 'VEDACIT_XML';
SELECT 'ciclos' consulta, id, execucao_id, inicio_em, fim_em, atualizado_em,
       conexao, status_ciclo, arquivos_validos, arquivos_rejeitados,
       selecionados, enviados, pendentes, saldo, bloqueios, timeouts_ambiguos,
       xml_habilitado, xml_avaliados, xml_enviados, xml_ja_processados,
       xml_pendentes, xml_erros, erros_comprovante, motivo_falha
FROM dbo.tb_work_sftp_cliente_execucao
WHERE sftp_cliente = 'VEDACIT' AND inicio_em >= '2026-09-11T17:00:00'
ORDER BY inicio_em;
SELECT 'telemetria' consulta, CAST(data_evento AS DATE) dia, rota, status_http,
       COUNT_BIG(*) chamadas, MIN(data_evento) primeira, MAX(data_evento) ultima
FROM dbo.tb_esl_request_telemetria
WHERE destino = 'VEDACIT' AND data_evento >= '2026-09-11T22:04:00'
GROUP BY CAST(data_evento AS DATE), rota, status_http;
SELECT 'estado_bruto' consulta, sftp_cliente, status_dados, status_canhoto,
       canhoto_classificacao_operacional classificacao, COUNT_BIG(*) registros,
       COUNT(DISTINCT chave_cte) ctes, MIN(data_processamento_dados) primeira_xml,
       MAX(data_processamento_dados) ultima_xml, MAX(data_processamento_canhoto) ultimo_pod
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
GROUP BY sftp_cliente, status_dados, status_canhoto, canhoto_classificacao_operacional;
GO
-- Classificação independente: XML por CT-e; comprovante por NF-e/CT-e efetivo.
WITH etapas AS (
    SELECT id, 'DADOS' etapa, status_dados situacao, data_processamento_dados data_etapa,
           CAST(NULL AS VARCHAR(40)) bloqueio, data_processamento,
           CASE WHEN NULLIF(chave_cte, '') IS NOT NULL THEN CONCAT('CTE:', chave_cte)
                ELSE CONCAT('LOG:', id) END documento
    FROM dbo.tb_log_integracao WHERE sistema_destino = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
    UNION ALL
    SELECT l.id, 'COMPROVANTE', CASE WHEN c.chave_nfe IS NOT NULL THEN 'SUCESSO' ELSE l.status_canhoto END,
           CASE WHEN c.chave_nfe IS NOT NULL THEN c.primeira_confirmacao_em ELSE l.data_processamento_canhoto END,
           CASE WHEN c.chave_nfe IS NOT NULL THEN NULL ELSE l.canhoto_classificacao_operacional END,
           l.data_processamento,
           CASE WHEN NULLIF(l.chave_nfe, '') IS NOT NULL AND NULLIF(COALESCE(NULLIF(l.canhoto_chave_cte_efetiva, ''), l.chave_cte), '') IS NOT NULL
                THEN CONCAT('POD:', l.chave_nfe, ':', COALESCE(NULLIF(l.canhoto_chave_cte_efetiva, ''), l.chave_cte))
                ELSE CONCAT('LOG:', l.id) END
    FROM dbo.tb_log_integracao l LEFT JOIN dbo.tb_confirmacao_comprovante c
      ON c.chave_nfe = l.chave_nfe AND c.chave_cte = COALESCE(NULLIF(l.canhoto_chave_cte_efetiva, ''), l.chave_cte)
    WHERE l.sistema_destino = 'VEDACIT' AND (l.arquivado = 0 OR l.arquivado IS NULL)
), ordenadas AS (
    SELECT *, ROW_NUMBER() OVER (PARTITION BY etapa, documento ORDER BY
      CASE WHEN situacao IN ('SUCESSO','ENVIADO','PROCESSADO') AND data_etapa IS NOT NULL THEN 0
           WHEN situacao IN ('SUCESSO','ENVIADO','PROCESSADO') THEN 1 ELSE 2 END,
      CASE WHEN situacao IN ('SUCESSO','ENVIADO','PROCESSADO') AND data_etapa IS NOT NULL THEN data_etapa END,
      data_processamento DESC, id DESC) ordem
    FROM etapas
), classificadas AS (
    SELECT *, CASE WHEN situacao IN ('NAO_APLICAVEL','IGNORADO') THEN 'IGNORADO'
      WHEN situacao IN ('SUCESSO','ENVIADO','PROCESSADO') AND data_etapa IS NOT NULL THEN 'SUCESSO'
      WHEN situacao IN ('SUCESSO','ENVIADO','PROCESSADO') AND etapa = 'COMPROVANTE' THEN 'CONFIRMADO_SEM_DATA'
      WHEN situacao IN ('SUCESSO','ENVIADO','PROCESSADO') THEN 'SEM_CONFIRMACAO'
      WHEN (etapa = 'DADOS' AND situacao = 'PENDENTE_ORIGEM') OR bloqueio IN ('BLOQUEADO_ORIGEM','BLOQUEADO_DESTINO','TIMEOUT_AMBIGUO') THEN 'BLOQUEADO'
      WHEN situacao IN ('PENDENTE','PENDENTE_FOTO','PENDENTE_ORIGEM','PENDENTE_ENVIO','PENDENTE_TECNICO','EM_PROCESSAMENTO','ERRO_DESTINO','ERRO_VALIDACAO') THEN 'PENDENTE'
      ELSE 'SEM_CONFIRMACAO' END classe
    FROM ordenadas WHERE ordem = 1
)
SELECT 'documentos_indicador' consulta, e.etapa, e.classe, e.id, e.situacao, e.bloqueio, e.data_etapa,
       l.sftp_cliente, SUBSTRING(l.chave_nfe,26,9) numero_nf, RIGHT(l.chave_nfe,8) nfe_final,
       SUBSTRING(l.chave_cte,26,9) numero_cte, RIGHT(l.chave_cte,8) cte_final,
       RIGHT(l.canhoto_chave_cte_efetiva,8) cte_efetivo_final,
       l.occurrence_id, l.status_dados, l.status_canhoto, l.tentativas_dados, l.tentativas_canhoto,
       l.data_processamento, l.data_processamento_dados, l.data_processamento_canhoto,
       CASE WHEN l.mensagem_erro_dados LIKE '%ORIGEM_XML_AUTENTICACAO_EM_ESPERA%' THEN 'XML_ESPERA_AUTORIZACAO'
            WHEN l.mensagem_erro_dados LIKE '%401%' THEN 'XML_HTTP_401'
            WHEN l.mensagem_erro_dados LIKE '%XML%' OR l.mensagem_erro_dados LIKE '%dados%' THEN 'XML_DADOS_NAO_CONFIRMADOS'
            WHEN NULLIF(l.mensagem_erro_dados,'') IS NOT NULL THEN 'OUTRO' ELSE 'SEM_ERRO' END motivo_xml,
       CASE WHEN l.mensagem_erro_canhoto LIKE '%timed out%' THEN 'READ_TIMEOUT'
            WHEN l.mensagem_erro_canhoto LIKE '%TIMEOUT%' OR l.mensagem_erro_canhoto LIKE '%desconhecido%' THEN 'RESULTADO_AMBIGUO'
            WHEN l.mensagem_erro_canhoto LIKE '%digitaliza%' THEN 'RECUSA_DIGITALIZACAO'
            WHEN l.mensagem_erro_canhoto LIKE '%incompat%' THEN 'RECUSA_INCOMPATIVEL'
            WHEN l.mensagem_erro_canhoto LIKE '%nome%' OR l.mensagem_erro_canhoto LIKE '%chaves%' THEN 'NOME_CHAVES_INVALIDOS'
            WHEN l.mensagem_erro_canhoto LIKE '%XML%' OR l.mensagem_erro_canhoto LIKE '%dados%' THEN 'DEPENDENCIA_XML_DADOS'
            WHEN l.mensagem_erro_canhoto LIKE '%indispon%' THEN 'COMPROVANTE_INDISPONIVEL'
            WHEN NULLIF(l.mensagem_erro_canhoto,'') IS NOT NULL THEN 'OUTRO' ELSE 'SEM_ERRO' END motivo_pod
FROM classificadas e JOIN dbo.tb_log_integracao l ON l.id=e.id
ORDER BY e.etapa, e.classe, e.id;
GO
-- Histórico arquivado pode explicar os sucessos sem data, sem torná-los novos envios.
WITH xml AS (
 SELECT chave_cte, MIN(data_processamento_dados) primeira_confirmacao
 FROM dbo.tb_log_integracao
 WHERE sistema_destino='VEDACIT' AND status_dados IN ('SUCESSO','ENVIADO','PROCESSADO')
 GROUP BY chave_cte
)
SELECT 'historico_xml' consulta, l.id, l.status_dados,
       x.primeira_confirmacao historico_primeira_confirmacao,
       CASE WHEN EXISTS (SELECT 1 FROM dbo.tb_log_integracao a
         WHERE a.sistema_destino='VEDACIT' AND a.chave_cte=l.chave_cte AND a.status_dados='SUCESSO' AND a.arquivado=1)
         THEN 1 ELSE 0 END sucesso_arquivado,
       CASE WHEN EXISTS (SELECT 1 FROM dbo.tb_confirmacao_comprovante c
         WHERE c.chave_nfe=l.chave_nfe AND c.chave_cte=COALESCE(NULLIF(l.canhoto_chave_cte_efetiva,''),l.chave_cte))
         THEN 1 ELSE 0 END comprovante_confirmado
FROM dbo.tb_log_integracao l LEFT JOIN xml x ON x.chave_cte=l.chave_cte
WHERE l.sistema_destino='VEDACIT' AND (l.arquivado=0 OR l.arquivado IS NULL)
  AND (l.status_dados='PENDENTE_ORIGEM' OR (l.status_dados='SUCESSO' AND l.data_processamento_dados IS NULL));
SELECT 'protecao' consulta, name, is_disabled FROM sys.triggers
WHERE object_id=OBJECT_ID(N'dbo.tr_log_preserva_confirmacao_comprovante');
