-- Réplica SQLite da entidade LogIntegracaoModel para o teste local Java/Rust.
-- Os índices de consulta SELIA seguem a migration V6. A tabela não cria
-- unicidade artificial para esconder uma corrida na lógica de recebimento.
CREATE TABLE tb_log_integracao (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    occurrence_id INTEGER,
    chave_nfe TEXT,
    chave_cte TEXT,
    freight_id INTEGER,
    intelipost_pre_shipment_list INTEGER,
    logistics_provider_shipment_list INTEGER,
    order_number TEXT,
    volume_number TEXT,
    cursor_next_id INTEGER,
    status TEXT NOT NULL,
    sistema_destino TEXT,
    request_payload TEXT,
    response_payload TEXT,
    erro TEXT,
    status_dados TEXT,
    status_canhoto TEXT,
    mensagem_erro_dados TEXT,
    mensagem_erro_canhoto TEXT,
    canhoto_referencia TEXT,
    canhoto_mime_type TEXT,
    canhoto_origem TEXT,
    sftp_cliente TEXT,
    arquivado INTEGER NOT NULL DEFAULT 0,
    arquivado_em TIMESTAMP,
    arquivado_motivo TEXT,
    canhoto_chave_cte_efetiva TEXT,
    canhoto_reconciliacao_tipo TEXT,
    canhoto_reconciliacao_motivo TEXT,
    canhoto_classificacao_operacional TEXT,
    canhoto_classificado_em TIMESTAMP,
    data_processamento_dados TIMESTAMP,
    data_processamento_canhoto TIMESTAMP,
    tentativas_dados INTEGER NOT NULL DEFAULT 0,
    tentativas_canhoto INTEGER NOT NULL DEFAULT 0,
    data_processamento TIMESTAMP NOT NULL,
    dados_acao_operacional TEXT,
    canhoto_acao_painel TEXT,
    dados_proxima_avaliacao_em TIMESTAMP,
    canhoto_proxima_avaliacao_em TIMESTAMP,
    dados_pendente_desde TIMESTAMP,
    canhoto_pendente_desde TIMESTAMP
);
CREATE INDEX ix_log_lista ON tb_log_integracao
    (sistema_destino, intelipost_pre_shipment_list, data_processamento, id);
CREATE INDEX ix_log_nfe ON tb_log_integracao
    (sistema_destino, chave_nfe, status, data_processamento DESC, id DESC);
