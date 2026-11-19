, motivos AS (
  SELECT c.*, UPPER(COALESCE(CASE WHEN c.etapa='DADOS' THEN l.mensagem_erro_dados ELSE l.mensagem_erro_canhoto END,'')) AS mensagem,
    CASE WHEN c.etapa='DADOS' THEN l.dados_pendente_desde ELSE l.canhoto_pendente_desde END AS pendente_desde,
    CASE WHEN c.etapa='DADOS' THEN l.dados_proxima_avaliacao_em ELSE l.canhoto_proxima_avaliacao_em END AS proxima_avaliacao
  FROM classificadas c JOIN dbo.tb_log_integracao l ON l.id=c.id
), operacionais AS (
  SELECT *, CASE
    WHEN classe IN ('SUCESSO','CONFIRMADO_SEM_DATA') THEN 'CONFIRMADO'
    WHEN classe='IGNORADO' THEN 'IGNORADO'
    WHEN classe='SEM_CONFIRMACAO' AND situacao IN ('SUCESSO','ENVIADO','PROCESSADO') THEN 'A_CONFERIR'
    WHEN mensagem LIKE 'SOAP_ANTERIOR_EM_ANDAMENTO%' THEN 'NOVA_TENTATIVA'
    WHEN bloqueio='TIMEOUT_AMBIGUO' OR mensagem LIKE '%SEM CONFIRMA%' OR mensagem LIKE '%SEM_CONFIRMA%'
      OR mensagem LIKE '%RESULTADO_DESCONHECIDO%' OR mensagem LIKE '%ENVIO_INICIADO_SEM_RESULTADO%'
      OR mensagem LIKE '%READ TIMED OUT%' OR mensagem LIKE '%SOCKETTIMEOUT%' OR mensagem LIKE '%TIMEOUT TOTAL DA VEDACIT%'
      OR mensagem LIKE '%INTERROMPIDO DURANTE ENVIO%' THEN 'AGUARDANDO_CONFIRMACAO'
    WHEN bloqueio='BLOQUEADO_DESTINO' OR situacao='ERRO_VALIDACAO' OR mensagem LIKE '%CORRELACAO_INVALIDA%'
      OR mensagem LIKE '%CHAVE_INVALIDA%' OR mensagem LIKE '%VEDACIT RECUSOU%' THEN 'INTERVENCAO'
    WHEN bloqueio='BLOQUEADO_ORIGEM' OR situacao IN ('PENDENTE_ORIGEM','PENDENTE_FOTO')
      OR mensagem IN ('ORIGEM_XML_HTTP_401','ORIGEM_XML_HTTP_403','ORIGEM_XML_AUTENTICACAO_EM_ESPERA','ORIGEM_XML_AUSENTE')
      OR mensagem LIKE 'DATA_ENTREGA_%' THEN 'AGUARDANDO_ORIGEM'
    WHEN bloqueio='PENDENTE_TECNICO' OR situacao IN ('PENDENTE_TECNICO','ERRO_DESTINO') THEN 'NOVA_TENTATIVA'
    WHEN situacao IN ('RECEBIDO','PENDENTE','PENDENTE_ENVIO','EM_PROCESSAMENTO') THEN 'AGUARDANDO_PROCESSAMENTO'
    ELSE 'A_CONFERIR' END AS acao
  FROM motivos
)
