USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

-- Somente leitura. Mesma precedência/deduplicação de XML do contrato v2.
-- Datas são do último estado; este SELECT não reconstrói tentativas anteriores.
WITH normalizadas AS (
    SELECT id, data_processamento, data_processamento_dados AS data_etapa,
           UPPER(TRIM(COALESCE(status_dados, ''))) AS situacao,
           mensagem_erro_dados, tentativas_dados,
           CASE WHEN NULLIF(chave_cte, '') IS NOT NULL THEN CONCAT('CTE:', chave_cte)
                ELSE CONCAT('LOG:', CAST(id AS VARCHAR(30))) END AS documento
    FROM dbo.tb_log_integracao
    WHERE sistema_destino = 'VEDACIT' AND (arquivado = 0 OR arquivado IS NULL)
), ordenadas AS (
    SELECT *, ROW_NUMBER() OVER (
        PARTITION BY documento
        ORDER BY CASE WHEN situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') AND data_etapa IS NOT NULL THEN 0
                      WHEN situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') THEN 1 ELSE 2 END,
                 CASE WHEN situacao IN ('SUCESSO', 'ENVIADO', 'PROCESSADO') AND data_etapa IS NOT NULL
                      THEN data_etapa END ASC,
                 data_processamento DESC, id DESC
    ) AS ordem
    FROM normalizadas
), falhas AS (
    SELECT *, CASE
        WHEN mensagem_erro_dados = 'ORIGEM_XML_HTTP_401' THEN 'ACESSO_401'
        WHEN mensagem_erro_dados = 'ORIGEM_XML_HTTP_403' THEN 'ACESSO_403'
        WHEN mensagem_erro_dados = 'ORIGEM_XML_AUTENTICACAO_EM_ESPERA' THEN 'ESPERA_AUTENTICACAO'
        WHEN mensagem_erro_dados = 'ORIGEM_XML_AUSENTE' THEN 'XML_AUSENTE'
        WHEN mensagem_erro_dados LIKE 'SOAP_ANTERIOR_EM_ANDAMENTO%' THEN 'SOAP_ANTERIOR_EM_ANDAMENTO'
        WHEN mensagem_erro_dados LIKE 'ORIGEM_XML_%' THEN 'OUTRO_MOTIVO_ORIGEM_XML'
        WHEN mensagem_erro_dados IS NULL THEN 'SEM_MOTIVO'
        ELSE 'OUTRO_MOTIVO' END AS motivo
    FROM ordenadas
    WHERE ordem = 1 AND situacao IN ('ERRO_DESTINO', 'ERRO_VALIDACAO')
      AND data_etapa >= '20260901' AND data_etapa < '20260922'
)
SELECT 'xml_ultimo_estado' AS consulta, CAST(data_etapa AS DATE) AS dia, motivo,
       COUNT_BIG(*) AS documentos,
       SUM(CASE WHEN tentativas_dados > 1 THEN 1 ELSE 0 END) AS contador_avaliacoes_maior_que_um,
       MIN(tentativas_dados) AS menor_contador, MAX(tentativas_dados) AS maior_contador
FROM falhas
GROUP BY CAST(data_etapa AS DATE), motivo
ORDER BY dia, motivo;

-- Saldo bruto do worker. Não equivale automaticamente à deduplicação por etapa.
SELECT 'saldo_bruto_comprovante' AS consulta, canhoto_classificacao_operacional AS classificacao,
       COUNT_BIG(*) AS registros
FROM dbo.tb_log_integracao
WHERE sistema_destino = 'VEDACIT' AND sftp_cliente = 'VEDACIT'
  AND (arquivado = 0 OR arquivado IS NULL)
  AND canhoto_classificacao_operacional IN
      ('PENDENTE_ENVIO', 'PENDENTE_TECNICO', 'BLOQUEADO_ORIGEM', 'BLOQUEADO_DESTINO', 'TIMEOUT_AMBIGUO')
GROUP BY canhoto_classificacao_operacional;

-- Os ciclos preservam contagens, mas não todas as causas individuais das avaliações.
SELECT TOP (12) 'ciclo' AS consulta, id, inicio_em, fim_em, atualizado_em,
       conexao, status_ciclo, selecionados, enviados, pendentes, xml_avaliados,
       xml_enviados, xml_ja_processados, xml_pendentes, xml_erros, erros_comprovante,
       saldo, bloqueios, timeouts_ambiguos,
       CASE WHEN motivo_falha IS NULL THEN NULL
            WHEN motivo_falha LIKE 'XML_FALHAS_MISTAS:%' THEN 'XML_FALHAS_MISTAS'
            WHEN motivo_falha LIKE 'XML_ACESSO_ORIGEM:%' THEN 'XML_ACESSO_ORIGEM'
            WHEN motivo_falha LIKE 'XML_RETIDO:%' THEN 'XML_RETIDO'
            WHEN motivo_falha LIKE 'PROCESSAMENTO:%' THEN 'PROCESSAMENTO'
            WHEN motivo_falha LIKE 'TIMEOUT_COMPROVANTE:%' THEN 'TIMEOUT_COMPROVANTE'
            ELSE 'OUTRO_MOTIVO' END AS motivo
FROM dbo.tb_work_sftp_cliente_execucao
WHERE sftp_cliente = 'VEDACIT' AND inicio_em >= '20260921' AND inicio_em < '20260922'
ORDER BY inicio_em DESC, id DESC;
