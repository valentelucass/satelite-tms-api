SELECT id, sistema_destino AS sistemaDestino, etapa, acao, pendente_desde AS pendenteDesde, proxima_avaliacao AS proximaAvaliacao,
  COALESCE((SELECT MAX(e.ocorrido_em) FROM dbo.tb_integracao_etapa_evento e
    WHERE e.log_id=o.id AND e.etapa=o.etapa AND e.tipo='AVALIACAO'),data_etapa) AS ultimaAvaliacao,
  CASE WHEN etapa='DADOS' AND LEN(chave_cte)=44 THEN CONCAT('CT-e ', SUBSTRING(chave_cte,26,9))
       WHEN LEN(chave_nfe)=44 THEN CONCAT('NF-e ', SUBSTRING(chave_nfe,26,9)) ELSE 'Documento sem identificação' END AS documento,
  CASE WHEN mensagem IN ('ORIGEM_XML_HTTP_401','ORIGEM_XML_HTTP_403') THEN 'ACESSO_ORIGEM_RECUSADO'
       WHEN mensagem='ORIGEM_XML_AUTENTICACAO_EM_ESPERA' THEN 'ESPERA_ACESSO_ORIGEM'
       WHEN mensagem LIKE 'DATA_ENTREGA_%' THEN 'METADADO_ENTREGA'
       WHEN mensagem='ORIGEM_XML_AUSENTE' THEN 'DOCUMENTO_AUSENTE' ELSE acao END AS motivo
FROM operacionais o
