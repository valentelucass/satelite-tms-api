# Origem ESL Cloud - Rodogarcia

## Endpoint

| Item | Valor |
|---|---|
| Metodo | `GET` |
| Base URL | `${RODOGARCIA_API_BASE_URL}` |
| Path | `${RODOGARCIA_CUSTOMER_OCCURRENCES_PATH}` |
| Path validado | `/api/customer/invoice_occurrences` |
| Autenticacao | `Authorization: Bearer <token_de_cliente>` |
| PPG | `${RODOGARCIA_TOKEN_PPG}` |
| Vedacit | `${RODOGARCIA_TOKEN_VEDACIT}` |

## JSON de lote mapeado

```json
{
  "data": [
    {
      "id": 277218587,
      "occurrence_at": "2026-05-18T08:41:01.000-03:00",
      "invoice": {
        "id": 96618816,
        "key": "44_DIGITOS_CHAVE_NFE",
        "series": "17",
        "number": "418454"
      },
      "freight": {
        "id": 49990543,
        "cte_key": "44_DIGITOS_CHAVE_CTE"
      },
      "occurrence": {
        "id": 30049,
        "code": 110,
        "description": "CTE Emitido"
      }
    }
  ],
  "paging": {
    "next_id": 277218793,
    "size": 20
  }
}
```

## DTO de origem

| Campo JSON | Tipo Java | Obrigatorio | Observacao |
|---|---|---|---|
| `data` | `List<EslInvoiceOccurrence>` | Sim | Lista de ocorrencias. |
| `paging` | `EslPaging` | Sim | Cursor do lote. |
| `data[].id` | `Long` | Sim | ID da ocorrencia ESL. |
| `data[].occurrence_at` | `OffsetDateTime` | Sim | Data/hora com offset. |
| `data[].invoice.id` | `Long` | Sim | ID interno da NF. |
| `data[].invoice.key` | `String` | Sim | Chave NFe com 44 digitos. |
| `data[].invoice.series` | `String` | Sim | Serie da NF. |
| `data[].invoice.number` | `String` | Sim | Numero da NF. |
| `data[].freight.id` | `Long` | Sim | ID do frete. |
| `data[].freight.cte_key` | `String` | Sim | Chave CTe. |
| `data[].occurrence.id` | `Long` | Sim | ID do tipo de ocorrencia no ESL. |
| `data[].occurrence.code` | `Integer` | Sim | Codigo funcional da ocorrencia. |
| `data[].occurrence.description` | `String` | Sim | Descricao funcional. |
| `paging.next_id` | `Long` | Condicional | Cursor para a proxima pagina. |
| `paging.size` | `Integer` | Sim | Quantidade retornada no lote. |

## Regra de filtro obrigatoria

Processar somente:

```java
occurrence.getCode() == 1
```

Interpretacao:

| Codigo ESL | Descricao esperada | Acao |
|---|---|---|
| `1` | Entrega Realizada | Enviar para o destino configurado. |
| Diferente de `1` | Qualquer outra ocorrencia | Registrar como `IGNORADO` e nao enviar ao destino. |

## Cursor de paginacao

1. Primeira consulta sem cursor.
2. Ler `paging.next_id`.
3. Persistir o cursor por cliente/token.
4. Proxima consulta com `?next_id=<valor_persistido>`.
5. Repetir ate `data` vir vazio ou a janela retroativa ultrapassar a data final.
6. Ao completar cada lote de `INTEGRATION_MAX_PAGES_PER_CYCLE` paginas, pausar por `ETL_PAGINATION_PACING_PAUSE_MS` e retomar do cursor mantido em memoria.

Aplicar intervalo minimo de 2 segundos entre chamadas a origem ESL (`ESL_MIN_INTERVAL_BETWEEN_REQUESTS_MS=2000`).
Quando a origem retornar HTTP 429, aplicar pausa bloqueante (`ESL_TOO_MANY_REQUESTS_BACKOFF_MS`, padrao 120000 ms) e repetir a mesma chamada sem encerrar o destino.

Exemplo:

```http
GET /api/customer/invoice_occurrences?next_id=277218793 HTTP/1.1
Host: rodogarcia.eslcloud.com.br
Authorization: Bearer ${RODOGARCIA_TOKEN_PPG}
Accept: application/json
```

## Idempotencia

Chave logica recomendada:

```text
cliente + sistema_destino + occurrence_id + chave_nfe
```

Se a chave ja existir como `ENVIADO`, nao reenviar. Se existir como `ERRO_DESTINO` ou `REPROCESSAR`, permitir nova tentativa controlada.

## Limites por periodo

Para fluxos ESL baseados em data, controlar nova extracao da mesma janela em memoria:

| Periodo consultado | Cooldown |
|---|---|
| Ate 30 dias | Sem cooldown adicional. |
| De 31 dias ate 6 meses | 1 hora. |
| Acima de 6 meses | 12 horas. |
