# Modelagem de APIs - Satelite TMS

Data de consolidacao: 2026-06-17.

Este documento registra o fluxo oficial validado para extracao Rodogarcia / ESL Cloud via API de cliente maquina a maquina.

## 1. Variaveis de ambiente

| Variavel | Uso |
|---|---|
| `INTEGRATION_SCHEDULER_INTERVAL_MS` | Intervalo de execucao do orquestrador ETL. Valor atual esperado: `900000` ms. |
| `RODOGARCIA_API_BASE_URL` | Base da origem ESL Cloud. Valor esperado: `https://rodogarcia.eslcloud.com.br`. |
| `RODOGARCIA_CUSTOMER_OCCURRENCES_PATH` | Caminho oficial de lote. Valor esperado: `/api/customer/invoice_occurrences`. |
| `RODOGARCIA_TOKEN_PPG` | Token de cliente Rodogarcia para extracao das ocorrencias destinadas a PPG / OK Entrega. |
| `RODOGARCIA_TOKEN_VEDACIT` | Token de cliente Rodogarcia para extracao das ocorrencias destinadas a Vedacit / MultiTMS. |
| `ESL_MIN_INTERVAL_BETWEEN_REQUESTS_MS` | Intervalo minimo entre chamadas a origem ESL. Valor padrao: `2000` ms. |
| `ESL_PERIOD_FREE_LIMIT_DAYS` | Janela consultada sem cooldown adicional. Valor padrao: `30` dias. |
| `ESL_PERIOD_MEDIUM_LIMIT_DAYS` | Limite da janela media antes do cooldown longo. Valor padrao: `183` dias. |
| `ESL_PERIOD_MEDIUM_COOLDOWN_MS` | Cooldown para nova extracao de janelas entre 31 dias e 6 meses. Valor padrao: `3600000` ms. |
| `ESL_PERIOD_LONG_COOLDOWN_MS` | Cooldown para nova extracao de janelas acima de 6 meses. Valor padrao: `43200000` ms. |
| `PPG_API_BASE_URL` | Base REST do destino OK Entrega. |
| `PPG_API_USER` / `PPG_API_PASSWORD` | Credenciais de autenticacao do destino PPG. |
| `PPG_ENTREGADOR_ID` | Identificador do entregador configurado no OK Entrega. |
| `VEDACIT_API_BASE_URL` | Base dos servicos SOAP MultiTMS. |
| `VEDACIT_API_TOKEN` | Token fixo enviado no header SOAP da Vedacit. |

Tokens, senhas e credenciais nao devem ser gravados em logs nem versionados em exemplos reais.

## 2. Origem Rodogarcia / ESL Cloud

### 2.1 Endpoint canonico

| Item | Valor |
|---|---|
| Metodo | `GET` |
| URL | `${RODOGARCIA_API_BASE_URL}${RODOGARCIA_CUSTOMER_OCCURRENCES_PATH}` |
| Caminho validado | `/api/customer/invoice_occurrences` |
| Autenticacao | Header `Authorization: Bearer <token_de_cliente>` |
| Token PPG | `${RODOGARCIA_TOKEN_PPG}` |
| Token Vedacit | `${RODOGARCIA_TOKEN_VEDACIT}` |
| Resposta | `application/json` |

Exemplo de chamada:

```http
GET /api/customer/invoice_occurrences HTTP/1.1
Host: rodogarcia.eslcloud.com.br
Authorization: Bearer ${RODOGARCIA_TOKEN_PPG}
Accept: application/json
```

### 2.2 Envelope de resposta

A resposta do ESL Cloud vem sempre no formato de lote com duas propriedades de primeiro nivel:

- `data`: lista de ocorrencias.
- `paging`: metadados de paginacao.

Estrutura mapeada:

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

### 2.3 Campos da ocorrencia

| Campo | Tipo Java sugerido | Regra |
|---|---|---|
| `data[].id` | `Long` | ID unico da ocorrencia no ESL. Usar para idempotencia por destino. |
| `data[].occurrence_at` | `OffsetDateTime` | Data/hora do evento com offset. |
| `data[].invoice.id` | `Long` | ID interno da nota no ESL. |
| `data[].invoice.key` | `String` | Chave NFe. Validar 44 digitos numericos antes de enviar aos destinos. |
| `data[].invoice.series` | `String` | Serie da NF. Preservar zeros a esquerda. |
| `data[].invoice.number` | `String` | Numero da NF. |
| `data[].freight.id` | `Long` | ID do frete no ESL. |
| `data[].freight.cte_key` | `String` | Chave CTe. Preservar como texto. |
| `data[].occurrence.id` | `Long` | ID do cadastro de ocorrencia no ESL. |
| `data[].occurrence.code` | `Integer` | Codigo da ocorrencia. Somente `1` segue para envio aos destinos. |
| `data[].occurrence.description` | `String` | Descricao original da ocorrencia. |

## 3. Filtro funcional obrigatorio

O ETL deve processar somente entregas realizadas:

```java
occurrence.code == 1
```

Mapeamento funcional:

| Origem ESL | Significado | Acao |
|---|---|---|
| `occurrence.code = 1` | Entrega Realizada | Processar, montar payload do destino e registrar auditoria. |
| Qualquer outro codigo | Evento operacional, documental ou nao finalizador | Ignorar para envio aos destinos e registrar auditoria como `IGNORADO`. |

Exemplo: `occurrence.code = 110` com descricao `CTE Emitido` nao deve gerar baixa de entrega.

## 4. Paginacao por cursor

O cursor oficial da origem e `paging.next_id`.

Todas as chamadas para a origem ESL devem respeitar intervalo minimo de 2 segundos entre requests, configurado por `ESL_MIN_INTERVAL_BETWEEN_REQUESTS_MS`.

Fluxo:

1. Executar a primeira chamada sem cursor.
2. Ler `paging.next_id` da resposta.
3. Se `paging.next_id` existir, persistir o valor como proximo cursor do cliente.
4. Executar a proxima chamada informando o cursor como parametro `next_id`.
5. Repetir ate a pagina retornar sem `next_id`, com `data` vazio ou ate atingir limite operacional do ciclo.

Formato esperado da chamada paginada:

```http
GET /api/customer/invoice_occurrences?next_id=${cursor} HTTP/1.1
Host: rodogarcia.eslcloud.com.br
Authorization: Bearer ${RODOGARCIA_TOKEN_PPG}
Accept: application/json
```

Regras de persistencia:

| Regra | Descricao |
|---|---|
| Cursor por cliente | PPG e Vedacit possuem tokens diferentes; manter cursor independente para cada fluxo. |
| Avanco seguro | Persistir `next_id` somente depois de registrar os itens da pagina recebida para auditoria. |
| Reprocessamento | A tabela de log deve impedir duplicidade por `occurrence_id`, `chave_nfe` e `sistema_destino`. |
| Falha parcial | Se um item falhar, manter o registro em estado de erro e permitir retentativa sem perder o cursor ja recebido. |

## 4.1 Limites por periodo consultado

Quando um fluxo da ESL usar consulta por periodo, a nova extracao da mesma janela deve seguir os limites operacionais:

| Periodo consultado | Nova extracao da mesma janela |
|---|---|
| Ate 30 dias | Liberada sem cooldown adicional, respeitando o intervalo minimo entre requests. |
| De 31 dias ate 6 meses | Liberada apos 1 hora. |
| Acima de 6 meses | Liberada apos 12 horas. |

O fluxo atual de ocorrencias usa cursor (`next_id`) e nao envia datas na requisicao, mas a politica esta centralizada para ser reutilizada se uma consulta por periodo for ativada.

## 5. Orquestrador ETL

O orquestrador Spring Boot deve executar de acordo com `INTEGRATION_SCHEDULER_INTERVAL_MS`.

Fluxo por ciclo:

1. Carregar configuracoes de origem, destinos e banco.
2. Montar a lista de clientes ativos:
   - `PPG`: token `${RODOGARCIA_TOKEN_PPG}`, destino OK Entrega.
   - `VEDACIT`: token `${RODOGARCIA_TOKEN_VEDACIT}`, destino MultiTMS.
3. Para cada cliente, consultar `/api/customer/invoice_occurrences` usando `Authorization: Bearer`.
4. Desserializar o envelope `data` + `paging`.
5. Registrar cada item recebido em `tb_log_integracao`.
6. Aplicar filtro obrigatorio `occurrence.code == 1`.
7. Validar `invoice.key` com 44 digitos numericos.
8. Montar payload do destino correspondente.
9. Enviar ao destino.
10. Atualizar auditoria com status, request, response, erro e data de processamento.
11. Persistir `paging.next_id` para o proximo ciclo do cliente.

Estados recomendados:

| Status | Uso |
|---|---|
| `RECEBIDO` | Item gravado a partir do lote ESL. |
| `IGNORADO` | Codigo de ocorrencia diferente de `1`. |
| `VALIDADO` | Item apto para envio ao destino. |
| `ENVIADO` | Destino aceitou a ocorrencia. |
| `ERRO_VALIDACAO` | Chave NFe ausente/invalida ou campos obrigatorios faltando. |
| `ERRO_DESTINO` | API/SOAP de destino rejeitou ou falhou. |
| `REPROCESSAR` | Item aguardando nova tentativa. |

## 6. De-Para principal

| Origem ESL | PPG / OK Entrega | Vedacit / MultiTMS | Regra |
|---|---|---|---|
| `invoice.key` | `documento` | `Canhoto.ChaveAcesso` | Validar 44 digitos. |
| `invoice.number` | Campo auxiliar se exigido | `NumeroNotaFiscal` | Preservar texto na origem; converter quando o destino exigir numero. |
| `invoice.series` | Campo auxiliar se exigido | `SerieNotaFiscal` | Preservar zeros a esquerda. |
| `occurrence_at` | `dtentrega`, `dtregistro` | `DataOcorrencia`, `DataEntregaNota` | PPG em ISO; Vedacit em `dd/MM/yyyy HH:mm:ss`. |
| `occurrence.code = 1` | `tipoocorrenciaId = 1` | `TipoOcorrencia.CodigoIntegracao = "001"` | Somente entrega realizada. |
| `occurrence.description` | Auditoria | `TipoOcorrencia.Descricao` ou `Observacao` | Manter descricao original. |
| `freight.id` | Auditoria | Auditoria | Usar para rastreabilidade do frete. |
| `freight.cte_key` | Auditoria | Auditoria | Preservar como texto. |

## 7. Destino PPG / OK Entrega

Contrato principal:

| Item | Valor |
|---|---|
| Metodo | `POST` |
| URL | `${PPG_API_BASE_URL}/api/Ocorrenciaentregas` |
| Formato | REST JSON |
| Autenticacao | Login em `/api/Usuarios/login` com `${PPG_API_USER}` e `${PPG_API_PASSWORD}`; usar `AccessToken.id` retornado pelo OK Entrega. |

Campos obrigatorios identificados no Swagger para `Ocorrenciaentrega`:

- `documento`
- `documento_34`
- `tipoocorrenciaId`
- `entregadorId`
- `dtregistro`
- `tipoentrada`
- `latitude`
- `longitude`
- `pendencia`
- `criacaoToken`
- `alteracaoToken`
- `status`

Campos obrigatorios identificados no Swagger para `Ocorrenciaentregafoto`:

- `ocorrenciaentregaId`
- `tipofoto`
- `foto`
- `mime`
- `extensao`
- `criacaoToken`
- `alteracaoToken`
- `status`

Regra de canhoto para PPG:

- Imagem em JPEG.
- Dimensoes finais exatas: `1536x240` pixels.
- Resolucao final: `150 dpi`.
- Campo `foto`: `data:image/jpeg;base64,` + Base64 da imagem normalizada.
- Campo `mime`: `data:image/jpeg;base64`.
- Campo `extensao`: `jpeg`.
- Campo `tipofoto`: `C`.

## 8. Destino Vedacit / MultiTMS

Servicos SOAP:

| Servico | Uso |
|---|---|
| `${VEDACIT_API_BASE_URL}/Cargas.svc` | Consulta e confirmacao de cargas quando o fluxo exigir sincronismo de carga. |
| `${VEDACIT_API_BASE_URL}/NFe.svc` | Consulta de NFs vinculadas e envio de digitalizacao do canhoto. |
| `${VEDACIT_API_BASE_URL}/Ocorrencias.svc` | Envio de ocorrencias. |

Autenticacao SOAP:

```xml
<soapenv:Header>
  <Token xmlns="Token">${VEDACIT_API_TOKEN}</Token>
</soapenv:Header>
```

Operacoes usadas na baixa:

| Operacao | Servico | Uso |
|---|---|---|
| `AdicionarOcorrencia` | `Ocorrencias.svc` | Enviar evento de entrega realizada. |
| `EnviarDigitalizacaoCanhoto` | `NFe.svc` | Enviar imagem do canhoto. |

Regra de canhoto para Vedacit:

- Campo: `ImagemCanhotoBase64`.
- Conteudo: Base64 bruto.
- Nao incluir prefixo MIME.
- Nao enviar `data:image/jpeg;base64,`.

## 9. Auditoria SQL Server

Toda ocorrencia recebida deve gerar trilha em `tb_log_integracao`.

Chaves de rastreabilidade:

- `occurrence_id`
- `invoice_id`
- `freight_id`
- `chave_nfe`
- `sistema_destino`
- `cliente`
- `cursor_next_id`

Indices recomendados:

- Indice simples por `chave_nfe`.
- Indice composto por `chave_nfe`, `sistema_destino`, `status`.
- Indice unico de idempotencia por `occurrence_id`, `chave_nfe`, `sistema_destino`.

## 10. Blueprints especificos

Arquivos de especificacao imediata:

- `docs/blueprint/origem_esl.md`
- `docs/blueprint/destino_ppg.md`
- `docs/blueprint/destino_vedacit.md`
- `docs/blueprint/banco_auditoria.md`
