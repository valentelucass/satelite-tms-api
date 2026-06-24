# Banco de Auditoria - SQL Server

## Tabela

Nome: `dbo.tb_log_integracao`

Finalidade: registrar recebimento, filtro, envio, resposta e erro de cada ocorrencia trafegada pelo Satelite TMS.

## DDL sugerido

```sql
CREATE TABLE dbo.tb_log_integracao (
    id BIGINT IDENTITY(1,1) NOT NULL,
    data_criacao DATETIME2(3) NOT NULL CONSTRAINT DF_tb_log_integracao_data_criacao DEFAULT SYSUTCDATETIME(),
    data_atualizacao DATETIME2(3) NULL,
    data_ocorrencia DATETIME2(3) NULL,
    data_processamento DATETIME2(3) NULL,

    cliente VARCHAR(40) NOT NULL,
    sistema_origem VARCHAR(40) NOT NULL,
    sistema_destino VARCHAR(40) NOT NULL,
    etapa VARCHAR(60) NOT NULL,
    status VARCHAR(40) NOT NULL,

    occurrence_id BIGINT NULL,
    occurrence_type_id BIGINT NULL,
    occurrence_code BIGINT NULL,
    occurrence_description VARCHAR(255) NULL,

    invoice_id BIGINT NULL,
    freight_id BIGINT NULL,
    chave_nfe VARCHAR(44) NULL,
    numero_nf VARCHAR(30) NULL,
    serie_nf VARCHAR(20) NULL,
    chave_cte VARCHAR(44) NULL,

    cursor_next_id BIGINT NULL,
    tentativa BIGINT NOT NULL CONSTRAINT DF_tb_log_integracao_tentativa DEFAULT 0,
    http_status BIGINT NULL,

    request_payload TEXT NULL,
    response_payload TEXT NULL,
    erro TEXT NULL,
    observacao TEXT NULL,

    imagem_hash VARCHAR(128) NULL,
    correlation_id VARCHAR(100) NULL,

    CONSTRAINT PK_tb_log_integracao PRIMARY KEY CLUSTERED (id)
);
```

## Status recomendados

| Status | Uso |
|---|---|
| `RECEBIDO` | Ocorrencia gravada a partir do lote ESL. |
| `IGNORADO` | `occurrence.code` diferente de `1`. |
| `VALIDADO` | Registro pronto para envio. |
| `ENVIADO` | Destino aceitou o payload. |
| `ERRO_VALIDACAO` | Falta chave NFe ou campo obrigatorio. |
| `ERRO_DESTINO` | Falha HTTP/SOAP no destino. |
| `REPROCESSAR` | Registro marcado para nova tentativa. |

## Indices

Pesquisa rapida por chave NFe:

```sql
CREATE INDEX IX_tb_log_integracao_chave_nfe
ON dbo.tb_log_integracao (chave_nfe);
```

Consulta operacional por chave, destino e status:

```sql
CREATE INDEX IX_tb_log_integracao_chave_destino_status
ON dbo.tb_log_integracao (chave_nfe, sistema_destino, status)
INCLUDE (occurrence_id, data_ocorrencia, data_processamento);
```

Idempotencia por ocorrencia e destino:

```sql
CREATE UNIQUE INDEX UX_tb_log_integracao_idempotencia
ON dbo.tb_log_integracao (cliente, sistema_destino, occurrence_id, chave_nfe)
WHERE occurrence_id IS NOT NULL AND chave_nfe IS NOT NULL;
```

Fila de reprocessamento:

```sql
CREATE INDEX IX_tb_log_integracao_reprocessamento
ON dbo.tb_log_integracao (status, tentativa, data_atualizacao)
INCLUDE (cliente, sistema_destino, occurrence_id, chave_nfe);
```

## Regras de gravacao

| Momento | Acao |
|---|---|
| Recebimento do lote ESL | Inserir registro com `RECEBIDO`, `occurrence_id`, `chave_nfe`, `freight_id`, `cursor_next_id`. |
| Codigo diferente de `1` | Atualizar para `IGNORADO`. |
| Validacao de campos | Atualizar para `VALIDADO` ou `ERRO_VALIDACAO`. |
| Envio ao destino | Gravar `request_payload` antes do envio. |
| Retorno do destino | Gravar `response_payload`, `http_status`, `status` e `data_processamento`. |
| Excecao | Gravar `erro`, incrementar `tentativa` e marcar `ERRO_DESTINO` ou `REPROCESSAR`. |

## Observacao sobre `TEXT`

`TEXT` atende ao requisito de tipo SQL Server para payloads longos. Em implementacoes novas, `VARCHAR(MAX)` pode substituir `TEXT` se o projeto preferir evitar tipos legados.
