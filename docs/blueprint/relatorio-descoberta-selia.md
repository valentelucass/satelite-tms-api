# Relatório de descoberta — SELIA / Intelipost

Data da verificação: 2026-07-23.

## Escopo confirmado

O Satélite não cria pedidos ou despachos. O fluxo é exclusivamente:

```text
ESL / invoice_occurrences (entrega realizada) -> Intelipost /tracking/add/events
```

Nenhum `POST` de AddEvents, criação de pedido ou publicação de evento foi executado nesta investigação. Depois da implementação do receptor, foram feitos apenas testes HTTPS contra a nossa própria URL: chave inválida e payload propositalmente incompleto, sem gravar dados operacionais.

## Mudança de escopo comunicada pela SELIA

Em 23/07/2026, a SELIA informou que a modalidade de integração 100% API exige os dois sentidos do fluxo: a Intelipost envia uma Pré Lista de Postagem ao sistema da transportadora por `POST` em uma URL pública fornecida por nós; depois, a transportadora envia o rastreamento para a Intelipost pelo AddEvents. Isso substitui a hipótese anterior de que o Satélite seria somente consumidor de ocorrências ESL e emissor de AddEvents.

O receptor da Pré Lista foi implementado e publicado. A URL a ser cadastrada pela Intelipost é:

```text
https://satelite-api.rodogarcia.com.br/api/selia/intelipost/pre-shipment-list
```

Ele aceita somente `POST`, valida o header `logistic-provider-api-key` sem registrá-lo, rejeita a lista inteira quando o payload estiver incompleto, é idempotente por `intelipost_pre_shipment_list` e responde todos os pedidos e volumes exigidos pelo contrato. Para o rastreamento posterior, grava apenas a correlação técnica de NF-e, pedido e volume na auditoria permitida. O endpoint não inicia o agendamento ETL.

O campo de resposta `logistics_provider_shipment_list` recebe hoje um identificador técnico numérico gerado pela auditoria. A SELIA/Intelipost ainda deve confirmar que esse formato é aceito ou informar o identificador operacional que esperam receber.

## Evidências por consulta de leitura

| Consulta | Resultado | Conclusão |
|---|---|---|
| ESL `GET /api/customer/invoice_occurrences?occurrence_code=1` com o token SELIA | HTTP 200; `data` vazia | A rota e o token estão funcionais, mas não há ocorrência de entrega realizada disponível para descobrir os campos reais. |
| ESL `GET /api/customer/invoice_occurrences?occurrence_code=1&start=0` com o token SELIA | HTTP 200; `data` vazia | O cursor explícito não retornou registros; a resposta informou `last_id=296210501` e `next_id=null`. |
| ESL `GET /api/customer/invoice_occurrences?occurrence_code=1&since=2026-07-22T00:00:00-03:00` com o token SELIA | HTTP 429; corpo `Retry later` | A ESL aplicou limitação de taxa sem o header `ratelimit-reset`; respeitar o backoff configurado antes de nova consulta temporal. |
| Intelipost `GET /api/v1/info` com `api-key` e `logistic-provider-api-key` | HTTP 401 | Não foi possível validar as credenciais por esse endpoint. |
| Intelipost `GET /api/v1/info` com cada chave isoladamente | HTTP 401 | A chave de rastreamento pode ser restrita ao AddEvents, o endpoint pode não estar habilitado para a conta ou alguma credencial não é a chave de autenticação esperada. |
| Intelipost `OPTIONS /api/v1/tracking/add/events` com os headers configurados | HTTP 200; `Allow: POST, GET, HEAD` | A rota pública existe e aceita o método de envio documentado. Esta verificação não envia evento nem homologa as credenciais. |
| Intelipost `GET /api/v1/tracking/add/events` com os headers configurados | HTTP 400; tentativa de converter `add` em identificador numérico | O `GET` é atendido por outra rota de consulta; não é uma validação do AddEvents nem das credenciais de envio. Nenhum `POST` foi feito. |
| Intelipost `GET /api/v1/shipment_order/invoice_key/<NF-e de exemplo da documentação>` | HTTP 401; `Este pedido requer autenticação HTTP.` | A credencial atual não tem, ou não apresentou no formato esperado, acesso à consulta de pedido por chave da NF-e. |
| Intelipost `GET /api/v1/shipment_order/get_volumes/PEDIDO0001` | HTTP 401; `Este pedido requer autenticação HTTP.` | A credencial atual também não permite a consulta de volumes. |
| HTTPS `GET` na URL pública da PLP | HTTP 405 | O Tunnel, hostname e rota Spring Boot estão acessíveis; o método correto é somente `POST`. |
| HTTPS `POST` na URL pública da PLP com chave inválida | HTTP 401 | A validação de autenticação de entrada está ativa, sem expor a chave. |
| HTTPS `POST` com a chave configurada e lista propositalmente incompleta | HTTP 400 | A rota está ativa e rejeita o payload por inteiro antes de persistir a correlação. |

Os valores secretos não são registrados neste documento.

## O que já pode ser definido internamente

| Item | Situação |
|---|---|
| Endpoint de envio | Confirmado: `POST /api/v1/tracking/add/events`. |
| Filtro da origem | Confirmado: somente `occurrence.code == 1`. |
| Anexo de comprovante | Confirmado: enviar como `POD` quando a ESL disponibilizar URL. |
| `platform` | Pode ser definido pelo Satélite; sugestão `SATELITE_TMS`. |
| `platform-version`, `plugin`, `plugin-version` | São metadados do nosso conector e podem ser definidos por nós; não exigem informação operacional da Intelipost. |
| `ignore-event-rule` | Não enviar no fluxo normal; somente em reprocessamento autorizado. |

## Dados que não podem ser inferidos com segurança

1. A autenticação aceita pelo AddEvents: o resultado 401 em `/info` não prova que as chaves falharão no endpoint de rastreamento, mas também não as valida. A validação segura exige um pedido Intelipost real de homologação e um único POST controlado.
2. O `original_code` do evento: depende do DePara configurado na conta Intelipost. Não se deve assumir que o código ESL `1` já está mapeado.
3. O código DePara de entrega realizada: não se deve assumir que o código ESL `1` já está mapeado.
4. A confirmação de que o identificador técnico devolvido em `logistics_provider_shipment_list` é aceito na PLP. Não se deve presumir que ele substitui um número operacional exigido pela conta.
5. Uma ocorrência real de homologação, com CT-e e comprovante. A PLP passa a fornecer pedido e volume sem inferência, mas ainda é necessário comprovar que o evento será vinculado corretamente antes de habilitar o envio automático.

## Próxima ação técnica segura

1. Enviar à SELIA a URL pública acima, pedir o cadastro e solicitar uma PLP real de homologação.
2. Pedir confirmação do formato aceito para `logistics_provider_shipment_list` e o código DePara de entrega realizada.
3. Após a PLP, localizar a ocorrência ESL da mesma NF-e e validar CT-e e comprovante reais.
4. Somente então habilitar a whitelist dessa NF-e e fazer um único POST AddEvents autorizado. `APP_SELIA_ENABLED` permanece `false` até esse aceite.

As consultas Intelipost de leitura continuam úteis como contingência, mas não bloqueiam mais a homologação porque a PLP aceita fornece o par pedido/volume de forma explícita.

Não realizar varredura, tentativa de combinações de pedidos/volumes ou POST de sondagem em produção.
