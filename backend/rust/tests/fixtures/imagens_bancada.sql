-- IDs negativos mantêm a primeira lista SELIA com a mesma sequência nos dois bancos.
INSERT INTO tb_log_integracao (id, sistema_destino, status, data_processamento, request_payload)
VALUES
    (-20, 'PPG', 'SUCESSO', '2026-11-18 10:00:00.000', '{"foto":"data:image/png;base64,dGVzdGU="}'),
    (-21, 'VEDACIT', 'SUCESSO', '2026-11-18 10:00:00.000', '{"foto":"data:image/png;base64,dGVzdGU="}'),
    (-22, 'PPG', 'SUCESSO', '2026-11-18 10:00:00.000', '{}');
