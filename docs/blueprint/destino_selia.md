# Integração SELIA — ocorrências ESL para Intelipost AddEvents

Data da análise: 2026-07-22.

## Decisão de escopo

O gestor confirmou que SELIA seguirá o mesmo padrão de PPG e Vedacit:

1. O Satélite consulta somente as ocorrências na API ESL Cloud.
2. O Satélite filtra entrega realizada, occurrence.code igual a 1.
3. O Satélite envia a ocorrência para a API REST Intelipost AddEvents.

Não fazem parte desta integração: SFTP, OCOREN, NOTFIS, CONEMB, DOCCOB, Pré Lista de Postagem, criação de pedidos, cotação, despacho, PLP, etiquetas, cancelamento ou webhook de entrada.

As documentações EDI recebidas ficam fora do fluxo SELIA selecionado. As credenciais SFTP existentes permanecem protegidas no arquivo .env e não devem ser usadas por este destino.

## Fluxo operacional

ESL Cloud com credencial do cliente SELIA
  -> GET de ocorrências de NF-e
  -> filtro local occurrence.code igual a 1
  -> transformação do evento
  -> POST Intelipost AddEvents
  -> auditoria, cursor e nova tentativa por registro

O destino será agendado, terá cursor próprio e seguirá idempotência, tratamento individual de erro e auditoria já usados por PPG e Vedacit. Não haverá controller ou tela para executar o fluxo principal.

## Origem — ESL Cloud / Rodogarcia

Fonte: TMS ESL Cloud — Consultar Listagem das Ocorrências.

| Item | Regra |
|---|---|
| Método | GET |
| Endereço | RODOGARCIA_API_BASE_URL + RODOGARCIA_CUSTOMER_OCCURRENCES_PATH |
| Rota padrão | /api/customer/invoice_occurrences |
| Autenticação | Authorization Bearer com RODOGARCIA_TOKEN_SELIA |
| Filtro de origem | occurrence_code igual a 1 |
| Paginação | paging.next_id reenviado como start |
| Ritmo | respeitar o intervalo ESL já aplicado pelo projeto e tratar HTTP 429 |

O cliente RodogarciaClient já suporta a rota, o filtro de ocorrência, cursor start, consulta por NF-e e consulta por período. Nenhuma nova API de origem é necessária.

Campos usados no evento:

| Campo ESL | Uso |
|---|---|
| id | idempotência e auditoria SELIA |
| occurrence_at | data e hora do evento |
| occurrence.code | código original de entrega |
| occurrence.description | mensagem original |
| invoice.key | chave da NF-e |
| invoice.series | série da NF-e |
| invoice.number | número da NF-e |
| freight.cte_key | rastreabilidade e eventual busca de comprovante |

## Destino — Intelipost AddEvents

Fonte: Adicionar Evento de Rastreamento, Intelipost TMS Embarcador.

| Item | Regra |
|---|---|
| Método | POST |
| Base | https://api.intelipost.com.br/api/v1 |
| Path | /tracking/add/events |
| Content-Type | application/json |
| Sucesso esperado | HTTP 200 |
| Credenciais | api-key e logistic-provider-api-key, exclusivamente no .env |

Cabeçalhos do conector:

| Cabeçalho | Configuração |
|---|---|
| api-key | SELIA_INTELIPOST_API_KEY |
| logistic-provider-api-key | SELIA_INTELIPOST_LOGISTIC_PROVIDER_API_KEY |
| platform | SELIA_INTELIPOST_PLATFORM |
| platform-version | SELIA_INTELIPOST_PLATFORM_VERSION |
| plugin | SELIA_INTELIPOST_PLUGIN |
| plugin-version | SELIA_INTELIPOST_PLUGIN_VERSION |
| Content-Type | application/json |

Os valores de identificação do conector serão configurados pelo Satélite. Nenhum segredo pode ser incluído em código, documentação, payload de auditoria ou log.

### Dados enviados

O request AddEvents deve usar:

| Campo Intelipost | Origem ou regra |
|---|---|
| invoice_key | invoice.key da ESL |
| invoice_series | invoice.series da ESL |
| invoice_number | invoice.number da ESL |
| order_number | campo order_number retornado pela ocorrência ESL |
| volume_number | mesmo valor de order_number, conforme retorno da Intelipost |
| events.event_date | occurrence_at com timezone ISO-8601 |
| events.original_code | código configurado no DePara Intelipost para entrega realizada |
| events.original_message | occurrence.description |
| events.attachments | comprovante de entrega obrigatório, tipo POD |

O retorno técnico da Intelipost confirmou que volume_number deve ser sempre o número do pedido. O DTO ESL agora tolera order_number tanto na raiz da ocorrência quanto no frete. Se o campo não vier na resposta da ESL, o registro recebe erro controlado e não é enviado com valor inventado. A integração continua somente com a origem ESL; não será criada integração de pedido, despacho ou Pré Lista.

O comprovante de entrega é obrigatório nesta etapa. O Satélite consulta o comprovante pelo CT-e na ESL e envia sua URL como anexo POD do AddEvents. Se ele ainda não existir, o registro fica PENDENTE_FOTO e é reprocessado sem perder auditoria ou cursor.

### Resiliência

- O envio deve preservar ordem cronológica dos eventos.
- HTTP 429 deve aguardar o valor de ratelimit-reset quando presente; caso contrário, aplicar backoff configurado.
- HTTP 400, 401, 403, 404 e 500 devem ser registrados individualmente na auditoria, sem derrubar o lote.
- Duplicidade confirmada pelo destino deve ser conciliada como sucesso.
- Nenhuma chamada produtiva deve ser feita com NF-e ou volume inventados.

## Implementação concluída e aguardando homologação

1. SeliaClient OpenFeign criado para AddEvents, com log básico para não expor headers.
2. DTOs record criados em dto.selia, com tolerância a campos desconhecidos no DTO raiz.
3. SeliaIntegrationService criado para montar o request, exigir ambas as chaves, usar pedido como volume, anexar POD e respeitar rate limit.
4. Destino SELIA registrado no orquestrador, cursor, auditoria, idempotência, repescagem e whitelist próprios.
5. APP_SELIA_ENABLED permanece false até a homologação controlada.

## Configuração prevista

- APP_SELIA_ENABLED
- RODOGARCIA_TOKEN_SELIA
- SELIA_INTELIPOST_API_BASE_URL
- SELIA_INTELIPOST_API_KEY
- SELIA_INTELIPOST_PLATFORM
- SELIA_INTELIPOST_PLATFORM_VERSION
- SELIA_INTELIPOST_PLUGIN
- SELIA_INTELIPOST_PLUGIN_VERSION
- SELIA_NFE_WHITELIST_ENABLED
- SELIA_NFE_WHITELIST
- SELIA_INTELIPOST_CONNECT_TIMEOUT_MS
- SELIA_INTELIPOST_READ_TIMEOUT_MS

## Pendências externas para ativação

1. Receber a logistic-provider-api-key solicitada pela Intelipost.
2. Receber os valores de platform, platform-version, plugin e plugin-version.
3. Receber o código de entrega configurado no DePara Intelipost.
4. Confirmar uma NF-e de homologação cujo order_number seja retornado na ocorrência ESL; se o campo não vier, informar em qual campo da resposta ESL ele será disponibilizado.
