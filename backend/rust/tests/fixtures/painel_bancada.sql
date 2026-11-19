-- Dados exclusivos de homologação local. Os IDs não dependem de autoincrement.
INSERT INTO tb_log_integracao
    (id,sistema_destino,status,status_dados,status_canhoto,tentativas_canhoto,
     chave_nfe,occurrence_id,freight_id,data_processamento,arquivado)
VALUES
 (1001,'PPG','ERRO_DESTINO','SUCESSO','ERRO_DESTINO',2,'00000000000000000000000170004223890000000000',41,51,'2026-10-01 10:00:00.123',0),
 (1002,'PPG','PROCESSADO',NULL,NULL,0,NULL,42,52,'2026-10-02 10:00:00',0),
 (1003,'VEDACIT','ERRO_DESTINO','ERRO_DESTINO','PENDENTE_FOTO',1,'00000000000000000000000170004223890000000000',43,NULL,'2026-10-02 10:00:00',0),
 (1004,'SELIA','PENDENTE_FOTO',NULL,NULL,0,NULL,44,NULL,'2026-10-02 10:00:00',0),
 (1005,'SELIA_PLP','ACEITO_PLP','SUCESSO','NAO_APLICAVEL',0,NULL,NULL,NULL,'2026-10-02 10:00:00',0),
 (1006,'PPG','ERRO_DESTINO','ERRO_DESTINO','ERRO_DESTINO',0,NULL,NULL,NULL,'2026-10-02 10:00:00',1),
 (1007,'SUPPORTE','PROCESSADO','SUCESSO','SUCESSO',0,NULL,NULL,NULL,'2026-11-01 00:00:00',0);
UPDATE tb_log_integracao SET mensagem_erro_dados='aviso sintético',mensagem_erro_canhoto='foto sintética',
    canhoto_referencia='imagem-de-teste',canhoto_mime_type='image/jpeg',
    data_processamento_dados='2026-10-01 10:00:01',data_processamento_canhoto='2026-10-01 10:00:02' WHERE id=1001;
UPDATE tb_log_integracao SET sftp_cliente='VEDACIT',canhoto_classificacao_operacional='BLOQUEADO_ORIGEM' WHERE id=1003;
