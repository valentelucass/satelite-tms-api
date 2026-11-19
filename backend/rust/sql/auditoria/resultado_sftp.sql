COALESCE(e.resultado_execucao, CASE
  WHEN e.fim_em IS NULL THEN 'EM_EXECUCAO'
  WHEN e.motivo_falha LIKE 'INTERROMPIDO:%' THEN 'INTERROMPIDO'
  WHEN e.conexao = 'OK' AND e.status_ciclo = 'CONCLUIDO'
    THEN CASE WHEN e.pendentes > 0 OR e.xml_pendentes > 0 THEN 'COM_PENDENCIAS' ELSE 'CONCLUIDO' END
  WHEN e.conexao = 'OK' AND e.status_ciclo = 'FALHA' AND (
    (e.erros_comprovante = 0 AND e.xml_erros > 0 AND
      (e.motivo_falha LIKE 'XML_RETIDO:%' OR e.motivo_falha LIKE 'XML_ACESSO_ORIGEM:%'
       OR e.motivo_falha LIKE 'XML_FALHAS_MISTAS:%'))
    OR e.motivo_falha LIKE 'TIMEOUT_COMPROVANTE:%' OR e.motivo_falha LIKE 'RECUSA_DESTINO:%')
    THEN 'COM_PENDENCIAS'
  WHEN e.conexao='FALHA' OR e.motivo_falha LIKE 'XML_PROCESSAMENTO:%'
    OR e.motivo_falha LIKE 'CONEXAO:%' OR e.motivo_falha LIKE 'INVENTARIO:%'
    OR e.motivo_falha LIKE 'BANCO_FILA_PROCESSAMENTO:%' OR e.motivo_falha LIKE 'ADAPTADOR_AUSENTE:%'
    THEN 'FALHA_TECNICA'
  ELSE 'NAO_CLASSIFICADO' END)
