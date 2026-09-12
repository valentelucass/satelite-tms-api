USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Somente leitura; executar pelo helper com -Rollback. Sem identidades fiscais completas.
SELECT 'relogio' consulta, DB_NAME() database_atual, SYSDATETIME() agora;
SELECT TOP (6) 'ciclos' consulta, id, inicio_em, fim_em, atualizado_em,
       conexao, status_ciclo, arquivos_validos, arquivos_rejeitados, selecionados,
       enviados, pendentes, saldo, bloqueios, timeouts_ambiguos,
       xml_avaliados, xml_enviados, xml_ja_processados, xml_pendentes, xml_erros
FROM dbo.tb_work_sftp_cliente_execucao
WHERE sftp_cliente='VEDACIT' ORDER BY inicio_em DESC;
SELECT 'classificacoes_ativas' consulta, status_dados, status_canhoto,
       canhoto_classificacao_operacional classificacao, COUNT_BIG(*) registros
FROM dbo.tb_log_integracao
WHERE sistema_destino='VEDACIT' AND COALESCE(arquivado,0)=0
GROUP BY status_dados,status_canhoto,canhoto_classificacao_operacional;
-- Mesmo filtro inicial da repescagem noturna XML (limite padrao: 5 tentativas).
-- Exibe a omissao de arquivado na selecao; o servico impede seu processamento depois.
WITH candidatos AS (
 SELECT l.id, l.arquivado, l.data_processamento
 FROM dbo.tb_log_integracao l
 WHERE l.sistema_destino='VEDACIT' AND l.status='ERRO_DESTINO'
   AND l.status_dados='ERRO_DESTINO' AND NULLIF(LTRIM(RTRIM(l.chave_cte)),'') IS NOT NULL
   AND COALESCE(l.tentativas_dados,0)<5
   AND (LOWER(COALESCE(l.mensagem_erro_dados,l.erro,'')) LIKE '%401 unauthorized%'
     OR LOWER(COALESCE(l.mensagem_erro_dados,l.erro,'')) LIKE '%read timed out%'
     OR LOWER(COALESCE(l.mensagem_erro_dados,l.erro,'')) LIKE '%timeout%'
     OR LOWER(COALESCE(l.mensagem_erro_dados,l.erro,'')) LIKE '%connection%'
     OR LOWER(COALESCE(l.mensagem_erro_dados,l.erro,'')) LIKE '% 429 %'
     OR LOWER(COALESCE(l.mensagem_erro_dados,l.erro,'')) LIKE '% 500 %'
     OR LOWER(COALESCE(l.mensagem_erro_dados,l.erro,'')) LIKE '% 502 %'
     OR LOWER(COALESCE(l.mensagem_erro_dados,l.erro,'')) LIKE '% 503 %'
     OR LOWER(COALESCE(l.mensagem_erro_dados,l.erro,'')) LIKE '% 504 %')
), primeira_pagina AS (
 SELECT TOP (100) * FROM candidatos ORDER BY data_processamento,id
)
SELECT 'selecao_noturna_xml' consulta, 'todos' recorte, COUNT_BIG(*) registros,
       COUNT_BIG(CASE WHEN arquivado=1 THEN 1 END) arquivados
FROM candidatos
UNION ALL
SELECT 'selecao_noturna_xml','primeiros_100',COUNT_BIG(*),COUNT_BIG(CASE WHEN arquivado=1 THEN 1 END)
FROM primeira_pagina;
SELECT 'tecnicos_comprovante_ativos' consulta, COUNT_BIG(*) registros
FROM dbo.tb_log_integracao
WHERE sistema_destino='VEDACIT' AND sftp_cliente='VEDACIT' AND COALESCE(arquivado,0)=0
  AND status_dados='SUCESSO' AND status_canhoto='ERRO_DESTINO'
  AND canhoto_classificacao_operacional='PENDENTE_TECNICO'
  AND chave_nfe IS NOT NULL AND NULLIF(LTRIM(RTRIM(chave_cte)),'') IS NOT NULL;
SELECT 'xml_inventario_nunca_tentado' consulta, COUNT_BIG(*) registros
FROM dbo.tb_log_integracao l
WHERE l.sistema_destino='VEDACIT' AND l.sftp_cliente='VEDACIT' AND COALESCE(l.arquivado,0)=0
  AND l.status_dados='PENDENTE_ORIGEM' AND COALESCE(l.tentativas_dados,0)=0
  AND l.data_processamento_dados IS NULL AND NULLIF(LTRIM(RTRIM(l.mensagem_erro_dados)),'') IS NULL
  AND NOT EXISTS (SELECT 1 FROM dbo.tb_log_integracao h WHERE h.sistema_destino='VEDACIT'
                  AND h.chave_cte=l.chave_cte AND h.status_dados='SUCESSO');
