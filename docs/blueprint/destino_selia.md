# Integração SELIA — ocorrências ESL para Intelipost AddEvents

Data da análise: 2026-07-23.

## Decisão de escopo

Para a modalidade SELIA 100% API, o fluxo possui dois sentidos:

1. A Intelipost envia a Pré Lista de Postagem (PLP) para uma URL pública do Satélite.
2. O Satélite aceita ou rejeita integralmente a PLP e guarda somente a correlação técnica necessária.
3. O Satélite consulta as ocorrências de entrega realizada na API ESL Cloud.
4. O Satélite envia a ocorrência para a API REST Intelipost AddEvents.

Não fazem parte desta integração: SFTP, OCOREN, NOTFIS, CONEMB, DOCCOB, criação de pedidos, cotação, despacho, etiquetas, cancelamento ou webhooks além do receptor obrigatório de PLP.

As documentações EDI recebidas ficam fora do fluxo SELIA selecionado. As credenciais SFTP existentes permanecem protegidas no arquivo .env e não devem ser usadas por este destino.

## Fluxo operacional

Intelipost
  -> POST Pré Lista para a URL pública do Satélite
  -> aceite/rejeição integral e correlação técnica NF-e/pedido/volume
  -> ESL Cloud com credencial do cliente SELIA
  -> GET de ocorrências de NF-e
  -> filtro local occurrence.code igual a 1
  -> transformação do evento
  -> POST Intelipost AddEvents
  -> auditoria, cursor e nova tentativa por registro

O destino outbound será agendado, terá cursor próprio e seguirá idempotência, tratamento individual de erro e auditoria já usados por PPG e Vedacit. O controller de PLP não aciona o fluxo principal.

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

## Entrada — Intelipost Pré Lista de Postagem

| Item | Regra |
|---|---|
| URL pública | `https://satelite-api.rodogarcia.com.br/api/selia/intelipost/pre-shipment-list` |
| Método | `POST` |
| Autenticação | header `logistic-provider-api-key` comparado sem registrar o segredo |
| Toggle | `SELIA_INTELIPOST_PLP_ENABLED=true` habilita somente a entrada de PLP |
| Idempotência | `intelipost_pre_shipment_list` |
| Persistência | somente NF-e, pedido, volume e identificadores técnicos em `tb_log_integracao` |
| Resposta | eco integral de pedidos/volumes e aceite/rejeição total do contrato Intelipost |

O retorno atual usa o ID técnico de auditoria como `logistics_provider_shipment_list`. A SELIA/Intelipost deve confirmar que esse identificador é aceito; não se deve enviar PLP produtiva antes dessa confirmação.

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
| order_number | ocorrência ESL quando disponível; caso contrário, correlação da PLP aceita mais recente da mesma NF-e |
| volume_number | ocorrência ESL quando disponível; caso contrário, correlação da PLP aceita mais recente da mesma NF-e; deve ser distinto de order_number quando a conta Intelipost assim o cadastrar |
| events.event_date | occurrence_at com timezone ISO-8601 |
| events.original_code | código configurado no DePara Intelipost para entrega realizada |
| events.original_message | occurrence.description |
| events.attachments | comprovante de entrega obrigatório, tipo POD |

A Pré Lista de Postagem exemplifica pedido e volume diferentes para o mesmo envio. Portanto, o conector trata `order_number` e `volume_number` como campos independentes, tanto na ESL quanto na correlação da PLP. Se os dois não vierem por uma dessas fontes, o registro recebe erro controlado e não é enviado com valor inventado.

O comprovante de entrega é obrigatório nesta etapa. O Satélite consulta o comprovante pelo CT-e na ESL e envia sua URL como anexo POD do AddEvents. Se ele ainda não existir, o registro fica PENDENTE_FOTO e é reprocessado sem perder auditoria ou cursor.

### Resiliência

- O envio deve preservar ordem cronológica dos eventos.
- HTTP 429 deve aguardar o valor de ratelimit-reset quando presente; caso contrário, aplicar backoff configurado.
- HTTP 400, 401, 403, 404 e 500 devem ser registrados individualmente na auditoria, sem derrubar o lote.
- Duplicidade confirmada pelo destino deve ser conciliada como sucesso.
- Nenhuma chamada produtiva deve ser feita com NF-e ou volume inventados.

## Implementação concluída e aguardando homologação

1. SeliaClient OpenFeign criado para AddEvents, sem expor headers.
2. DTOs `record` criados em `dto.selia`, com tolerância a campos desconhecidos nos contratos de entrada.
3. `SeliaPreShipmentListController` e `SeliaPreShipmentListService` recebem a PLP pública, validam o header, respondem o contrato e preservam somente a correlação técnica.
4. `SeliaIntegrationService` monta AddEvents, exige ambas as chaves, usa identificadores da ESL ou a correlação de PLP, anexa POD e respeita rate limit.
5. A migration `V6__selia_plp_auditoria_correlacao.sql` foi aplicada exclusivamente em `SATELITE_TMS_AUDITORIA`.
6. `SELIA_INTELIPOST_PLP_ENABLED=true` está disponível para a homologação de entrada; `APP_SELIA_ENABLED=false` mantém o rastreamento automático desabilitado.

## Configuração prevista

- APP_SELIA_ENABLED
- RODOGARCIA_TOKEN_SELIA
- SELIA_INTELIPOST_API_BASE_URL
- SELIA_INTELIPOST_API_KEY
- SELIA_INTELIPOST_LOGISTIC_PROVIDER_API_KEY
- SELIA_INTELIPOST_PLATFORM
- SELIA_INTELIPOST_PLATFORM_VERSION
- SELIA_INTELIPOST_PLUGIN
- SELIA_INTELIPOST_PLUGIN_VERSION
- SELIA_NFE_WHITELIST_ENABLED
- SELIA_NFE_WHITELIST
- SELIA_INTELIPOST_CONNECT_TIMEOUT_MS
- SELIA_INTELIPOST_READ_TIMEOUT_MS
- SELIA_INTELIPOST_PLP_ENABLED

## Pendências externas para ativação

1. Enviar a URL pública à SELIA, pedir o cadastro e receber uma PLP real de homologação.
2. Confirmar o aceite do formato de `logistics_provider_shipment_list` retornado.
3. Receber o código de entrega configurado no DePara Intelipost.
4. Receber uma NF-e de homologação com ocorrência ESL, CT-e e comprovante reais, habilitar a whitelist somente dessa NF-e e fazer um único AddEvents autorizado.
