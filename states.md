# Estado Atual do Sistema

## Stack Tecnológica
- Java 17 com Spring Boot 4.1.0 e Maven Wrapper.
- Spring Cloud OpenFeign para integrações REST externas.
- Spring Web MVC para endpoints auxiliares de auditoria e quarentena.
- Spring Web Services, Jakarta XML Web Services API, JAX-WS RI e `jaxws-maven-plugin` para clientes SOAP da Vedacit/MultiTMS gerados a partir de WSDL.
- Spring Data JPA/Hibernate com Microsoft JDBC Driver para SQL Server.
- Banco SQL Server exclusivo `SATELITE_TMS_AUDITORIA`/`satelite_tms_auditoria` para auditoria, cursores e estado técnico da integração.
- Apache PDFBox e Java ImageIO/AWT para renderização, conversão, compressão e normalização de canhotos.
- WSDL/XSD da Vedacit empacotados em `src/main/resources/wsdl/vedacit` para criação de proxies SOAP dentro do Fat JAR.
- Lombok em entidades/modelos internos; testes com starters de teste Spring Boot para JPA, Web MVC e Web Services.
- Operação em Windows por `satelite.bat`, `build.bat` e scripts em `scripts/`; banco provisionado por `database/subir_database.bat`.

## Arquitetura e Padrões
- Aplicação middleware/ETL headless: o fluxo principal é acionado por Spring Scheduling em `OrquestradorEtlScheduler`, não por controller web.
- Arquitetura em camadas: `clients` concentra OpenFeign REST; `dto.rodogarcia` modela entrada ESL/Rodogarcia; `dto.ppg` modela saída PPG/OK Entrega; `services.etl`, `services.ppg`, `services.vedacit` concentram regras por domínio; `repositories` acessa auditoria SQL; `models` contém entidades JPA; `utils` guarda lógica pura de imagem/download.
- `SateliteApplication` habilita OpenFeign; `SchedulerConfig` habilita agendamento.
- `OrquestradorEtlService` coordena os destinos PPG, SELIA e Vedacit, respeitando toggles independentes, modo ciclo único e modo retroativo, e delega a cadência contínua de paginação/backoff para os serviços de fluxo e política ESL.
- O destino SELIA/Intelipost possui dois sentidos independentes: o receptor público de Pré Lista de Postagem (PLP) e o envio outbound AddEvents. `SELIA_INTELIPOST_PLP_ENABLED=true` habilita apenas o receptor para homologação; `APP_SELIA_ENABLED=false` mantém o envio automático de rastreamento desabilitado até a validação final.
- O guia operacional `docs/contexto/guia-postman-selia.md` documenta a PLP recebida, as consultas ESL, a validação do comprovante, o POST AddEvents controlado, as variáveis sem segredos, as dependências externas e os critérios de decisão por resposta HTTP.
- `EtlFluxoDestinoService` encapsula paginação por destino, cursor, whitelists, filtro de ocorrência, detecção de loop de cursor, circuit breaker por falhas de infraestrutura, avanço seguro do cursor e pacing por lote de páginas.
- `EtlRegistroService` aplica idempotência por log existente, processa pendências de canhoto, baixa comprovantes da ESL e atualiza o estado por registro.
- `EtlRepescagemService` executa repescagem de erros definitivos do ciclo e repescagem ativa, sem janela de tempo, para falhas parciais de canhoto Vedacit com dados já integrados.
- `EtlEstadoIntegracaoService` centraliza status, tentativas, datas de processamento e conversão para resultado operacional.
- `EtlResilienciaService` encapsula retries por erro transitório, backoff e limite de tentativas.
- `EslRequestPolicyService` impõe intervalo mínimo entre chamadas ESL, trata HTTP 429 com backoff bloqueante e retry transparente da mesma chamada, trata HTTP 5xx/falhas de transporte como transitórias e controla cooldown para consultas por período.
- `IntegracaoAuditoriaQueryRepository` concentra as consultas analíticas de auditoria operacional do Dashboard, incluindo evolução diária e resumo por entidade operacional, sempre com agregação, classificação de status e filtros temporais executados no SQL Server.
- Banco versionado por scripts SQL idempotentes em `database/sql/schema` e `database/sql/migration`; todo script deve usar `USE`, `SET ANSI_NULLS ON` e `SET QUOTED_IDENTIFIER ON`.
- Classes SOAP da Vedacit são geradas em `target/generated-sources/wsimport*`, devem usar as interfaces geradas pelo Maven e não devem ser editadas manualmente.
- Proxies SOAP da Vedacit são criados em `VedacitIntegrationService` pelas classes `Ocorrencias`, `NFe` e `CTe` geradas pelo `jaxws-maven-plugin`, carregando WSDLs locais por `getResource` a partir de `/wsdl/vedacit/...` para funcionar dentro do Fat JAR.
- Os endpoints efetivos da Vedacit continuam configuráveis por `VEDACIT_API_BASE_URL` e são aplicados via `BindingProvider`, substituindo o `soap:address` presente nos WSDLs empacotados.
- `wsimport` usa `-XautoNameResolution` para Ocorrências, NFe e CTe, evitando colisões de classes geradas quando o WSDL oficial expõe tipos com nomes repetidos.
- `build-helper-maven-plugin` registra os três diretórios `target/generated-sources/wsimport*` como fontes Maven, permitindo que IDEs reconheçam os contratos SOAP após a geração.
- O VS Code não deve filtrar `target` em `java.project.resourceFilters`, pois a extensão Java precisa ler `target/generated-sources/wsimport*`; a pasta pode permanecer oculta somente no Explorer e na busca.
- Logs, auditorias, cursores, quarentenas e estados técnicos de integração usam exclusão lógica obrigatória quando precisarem sair das leituras operacionais. Hard delete/`DELETE FROM`/`TRUNCATE` em rotinas comuns é proibido; use status, `ativo`, `deleted_at`, `arquivado` ou campo equivalente, com filtros explícitos nas consultas de produção.

## Fluxo de Dados e Integrações
- A SELIA comunicou em 23/07/2026 que a modalidade 100% API exige também a Pré Lista de Postagem: a Intelipost faz `POST` para a URL pública da transportadora e o Satélite responde o aceite/rejeição; o AddEvents continua sendo o sentido de saída. O receptor foi implementado com DTOs `record`, validação do header `logistic-provider-api-key`, idempotência por `intelipost_pre_shipment_list`, rejeição total de payload incompleto e correlação técnica mínima NF-e/pedido/volume na auditoria.
- Origem principal: Rodogarcia/ESL Cloud via REST OpenFeign em `RodogarciaClient`.
- Endpoint ESL de ocorrências: `GET ${RODOGARCIA_API_BASE_URL}${RODOGARCIA_CUSTOMER_OCCURRENCES_PATH}`, padrão `/api/customer/invoice_occurrences`, com `Authorization: Bearer <token>`.
- Parâmetros usados na origem: `start` para cursor, `invoice_key` para whitelists/repescagem, `since` para retroativo e para lookback incremental das últimas 24 horas, e `occurrence_code=1`.
- Comprovantes ESL: `GET /api/customer/freight_delivery_receipts?cte_key=...`.
- XML de CT-e ESL: `GET ${RODOGARCIA_CTE_XML_PATH:/api/ctes}?key=...`, usado quando `VEDACIT_SEND_CTE_XML_ENABLED=true` e com token `RODOGARCIA_MASTER_API_REST`.
- Destinos ativos: `PPG` com token ESL próprio `RODOGARCIA_TOKEN_PPG` e `VEDACIT` com token ESL próprio `RODOGARCIA_TOKEN_VEDACIT`; cada destino mantém cursor independente.
- SELIA é um destino REST implementado no mesmo padrão outbound de PPG e Vedacit: consulta ocorrências ESL com `RODOGARCIA_TOKEN_SELIA`, exige comprovante e envia AddEvents com ambas as chaves Intelipost. Quando a ESL não trouxer pedido/volume, o serviço usa exclusivamente a correlação da PLP aceita mais recente para a NF-e; sem essa correlação, falha de modo controlado. O runtime outbound permanece desabilitado até a homologação.
- O processo PM2 `Satelite-API-19090` executa o JAR do Satélite na porta local `19090`. O Cloudflare Tunnel publica exclusivamente o caminho `^/api/selia/intelipost/pre-shipment-list$` do hostname `satelite-api.rodogarcia.com.br` para `http://127.0.0.1:19090`; o catch-all do Tunnel retorna HTTP 404 para os demais caminhos. Após a publicação do JAR, o HTTPS externo confirmou `405` para `GET`, `401` para `POST` com chave inválida e `400` para `POST` autorizado com payload propositalmente incompleto; nenhum dado operacional foi gravado nesses testes.
- A Intelipost documenta consultas somente de leitura para localizar pedido por chave NF-e (`GET /shipment_order/invoice_key/{chave_nfe}`) e volumes pelo pedido (`GET /shipment_order/get_volumes/{pedido_envio}`). Elas seguem fora do runtime e são contingência opcional: ambas retornaram HTTP 401 com a credencial atualmente configurada, mas a PLP aceita passa a suprir a correlação necessária para o rastreamento.
- As credenciais SFTP Intelipost da SELIA permanecem isoladas no `.env`, mas EDI/OCOREN não faz parte do escopo selecionado e não deve ser ativado ou implementado.
- A paginação ESL continua até `data` vazio, encerramento por janela retroativa ou falha não recuperável; `INTEGRATION_MAX_PAGES_PER_CYCLE` define apenas quantas páginas compõem um lote antes da pausa `ETL_PAGINATION_PACING_PAUSE_MS`, sem encerrar o ciclo.
- Destino PPG/OK Entrega: `PpgClient` faz login em `/assets/ws/ws.0.loginapp.php` e envia ocorrência em `/assets/ws/ws.0.ocorrenciaentregacache_api.php`; `PpgAuthService` mantém token em memória por 13 dias.
- Destino Vedacit/MultiTMS: SOAP em `Ocorrencias.svc`, `NFe.svc` e `CTe.svc`, com token em header SOAP, timeouts configuráveis, WSDLs locais carregados do classpath e endpoints finais definidos por `VEDACIT_API_BASE_URL`.
- Canhotos PPG: imagem baixada da ESL, convertida para RGB/JPEG, recortada, redimensionada para `1536x240`, gravada a 150 DPI e enviada com prefixo `data:image/jpeg;base64,`.
- Canhotos Vedacit: imagem ou primeira página de PDF é convertida/comprimida em JPEG, limite máximo de 400 KB, Base64 bruto sem prefixo MIME.
- Persistência técnica: `dbo.tb_log_integracao` registra ocorrência, chave NFe, frete, cursor, status geral, status de dados, status de canhoto, tentativas, mensagens de erro, payloads e referência de canhoto; `dbo.tb_controle_cursor` armazena cursor por `sistema_destino`.
- Endpoints auxiliares expostos: `/api/auditoria/integracoes-clientes`, `/api/auditoria/integracoes-clientes/evolucao-diaria`, `/api/auditoria/integracoes-clientes/resumo-tabelas`, `/api/auditoria/logs/{id}/imagem`, `/api/etl/quarentena/erros` e `/api/etl/quarentena/{destino}/reprocessar`.

## Regras de Negócio Consolidadas
- O fluxo principal só deve processar entrega realizada: `occurrence.code == 1`. Outros códigos são ignorados e auditados.
- Controllers não devem acionar o fluxo principal de integração; endpoints web existem apenas para auditoria, imagem de canhoto e quarentena/reprocessamento manual.
- Ações SQL manuais ou automatizadas devem atingir exclusivamente `SATELITE_TMS_AUDITORIA`; é proibido tocar `ETL_SISTEMA`, `DASHBOARDS` ou qualquer outra database.
- O banco guarda apenas auditoria, status, cursor e erros. Não persistir fisicamente NF completa, imagem de canhoto ou massa operacional de domínio.
- O cursor só avança depois do processamento/auditoria da página. Em erro de registro no modo incremental, o cursor não avança para permitir retry.
- O modo incremental consulta a ESL com `since` calculado como agora menos `ETL_INCREMENTAL_LOOKBACK_HOURS`, padrão 24 horas, mantendo o cursor por destino para paginação e avanço seguro.
- Em carga retroativa, erros por registro são registrados e a paginação pode avançar em memória para não travar o histórico.
- Em carga retroativa, `RETROACTIVE_MAX_PAGES`/`retroactive.max-pages` não podem ultrapassar `INTEGRATION_MAX_PAGES_PER_CYCLE`; o valor resultante define o tamanho do lote antes de pacing, não um limite terminal do ciclo.
- `INTEGRATION_MAX_PAGES_PER_CYCLE` não encerra mais a paginação; ao atingir o lote configurado, o fluxo pausa por `ETL_PAGINATION_PACING_PAUSE_MS` (padrão 30000 ms) e retoma do cursor em memória/persistido.
- Cada destino tem whitelists independentes de NF-e: `PPG_NFE_WHITELIST_ENABLED/PPG_NFE_WHITELIST` e `VEDACIT_NFE_WHITELIST_ENABLED/VEDACIT_NFE_WHITELIST`.
- Registros com status final `ENVIADO` ou `IGNORADO` não são reenviados automaticamente.
- Status consolidados atuais: `RECEBIDO`, `IGNORADO`, `ENVIADO`, `PARCIAL`, `PENDENTE_FOTO`, `ERRO_DESTINO`, `SUCESSO` e `NAO_APLICAVEL`.
- PPG exige canhoto disponível; sem imagem, o registro fica `PENDENTE_FOTO` e não envia payload.
- Vedacit pode concluir dados e canhoto separadamente; se canhoto faltar, o registro pode ficar parcial com `status_canhoto=PENDENTE_FOTO`.
- Registros Vedacit com `status=ERRO_DESTINO`, `status_dados=SUCESSO`, `status_canhoto=ERRO_DESTINO` e `tentativas_canhoto < 3` são resgatados pela repescagem ativa sem limite de janela; o XML/dados é considerado já integrado e o retry executa somente download, compressão e envio SOAP do canhoto.
- Falhas parciais de canhoto preservam `status_dados=SUCESSO`, incrementam `tentativas_canhoto` a cada nova falha e entram na quarentena natural ao alcançar `tentativas_canhoto >= 3`.
- O dashboard de pendências deve exibir falhas parciais de canhoto ainda retryáveis como `Erro Parcial - Aguarda Retry`, evitando invisibilidade operacional.
- O resumo analítico de integrações para o Dashboard deve agrupar no SQL Server por entidade operacional derivada de `tb_log_integracao`: `sistema_destino - XML/Dados` e `sistema_destino - Canhoto`. Como o banco de auditoria não persiste uma coluna física de tabela de negócio (`tabela_codigo`/`tabela_busca`), não se deve inventar essa dimensão no Dashboard nem agregar os logs em memória.
- Duplicidade retornada por PPG ou Vedacit é conciliada como sucesso, não como erro fatal.
- O runtime Vedacit não deve ler WSDL por caminho de filesystem como `src/main/resources`; a leitura deve ser por classpath (`getResource`) e falhar explicitamente se o WSDL local não estiver empacotado.
- Erros HTTP transitórios ou de infraestrutura incluem 500, 502, 503, 504, timeouts e falhas de transporte; há backoff e limite de tentativas antes de quarentena/bloqueio de retry automático.
- Circuit breaker de infraestrutura abre por falhas consecutivas conforme `ETL_CIRCUIT_BREAKER_TRANSIENT_FAILURE_THRESHOLD`.
- A ESL deve respeitar intervalo mínimo entre requests (`ESL_MIN_INTERVAL_BETWEEN_REQUESTS_MS`, padrão 2000 ms); HTTP 429 aplica backoff bloqueante (`ESL_TOO_MANY_REQUESTS_BACKOFF_MS`, padrão 120000 ms) e repete a mesma chamada sem suspender o destino.
- Consultas por período usam cooldown: até 30 dias sem cooldown adicional, 31 a 183 dias com 1 hora, acima disso com 12 horas.
- Toda credencial, URL e token deve vir de `.env`, `application.properties` ou variável de ambiente; não pode haver segredo hardcoded.
- SELIA usa AddEvents como saída e PLP como entrada obrigatória na modalidade 100% API. A documentação de PLP demonstra que `order_number` e `shipment_order_volume_number` podem ter valores diferentes; o DTO ESL tolera `order_number` e `volume_number` na ocorrência ou no frete e, quando eles não vierem, o serviço usa somente os pares correlacionados da última PLP aceita para a mesma NF-e. Sem os dois identificadores por uma dessas fontes, falha de modo controlado, sem SFTP, NOTFIS ou inferência.
- O cliente REST SELIA deve enviar `api-key`, `logistic-provider-api-key`, `platform`, `platform-version`, `plugin`, `plugin-version` e `Content-Type: application/json`. O código de entrega deve vir do DePara Intelipost. O comprovante ESL é obrigatório e segue como anexo POD; HTTP `429` respeita `ratelimit-reset` quando a resposta o fornecer, sem quebrar a cronologia de eventos.
- A chave REST de Rastreamento/AddEvents da SELIA foi recebida e está exclusivamente em `SELIA_INTELIPOST_API_KEY` no `.env`. Sua existência evidencia que o REST também é um canal disponível, mas não autoriza chamadas produtivas nem resolve os metadados obrigatórios, o de-para de eventos ou o vínculo pedido/volume.
- A investigação somente de leitura da SELIA está registrada em `docs/blueprint/relatorio-descoberta-selia.md`: a ESL voltou a responder HTTP 200 ao consultar ocorrências com o token SELIA, mas retornou `data` vazia; a consulta temporal subsequente recebeu HTTP 429 sem `ratelimit-reset`. A Intelipost respondeu HTTP 401 ao `GET /info` com as chaves em conjunto ou isoladas. Esses resultados não autorizam POST de sondagem, nem permitem inferir pedido, volume ou DePara.
- A continuação da investigação confirmou por `OPTIONS` que `/tracking/add/events` expõe `POST`, mas uma chamada `GET` nessa URL atingiu outra rota e retornou HTTP 400; isso não valida a autenticação de envio. As consultas oficiais Intelipost por NF-e e por volumes retornaram HTTP 401 com as credenciais configuradas. Nenhum POST AddEvents foi executado.
- A Pré Lista de Postagem Intelipost é um `POST` de entrada da Intelipost para a URL pública da transportadora, autenticado por `logistic-provider-api-key`. O receptor implementado não registra a chave, payload bruto ou dados pessoais; valida toda a lista antes de gravar, responde todos os pedidos/volumes recebidos e grava somente identificadores técnicos de correlação em `tb_log_integracao`. A resposta usa o ID técnico da auditoria como `logistics_provider_shipment_list`, decisão pendente de aceite explícito da SELIA/Intelipost.
- O Satélite não cria cotações, despacho operacional, PLP/NOTFIS, etiquetas, cancelamentos ou webhooks adicionais para SELIA. A única entrada é o receptor obrigatório de Pré Lista, limitado ao aceite/rejeição técnico da PLP; a integração de saída continua restrita à publicação de ocorrências AddEvents com comprovante POD obrigatório. A ausência de comprovante mantém o registro em `PENDENTE_FOTO` para retry.
- A migration `database/sql/migration/V6__selia_plp_auditoria_correlacao.sql` adiciona à auditoria somente `intelipost_pre_shipment_list`, `logistics_provider_shipment_list`, `order_number` e `volume_number`, com índices de busca. Ela foi executada com sucesso exclusivamente em `SATELITE_TMS_AUDITORIA` por `database/subir_database.bat`.
- Os testes de PLP cobrem aceite válido, lista repetida, chave inválida, payload incompleto, ausência de vazamento do segredo e controller MVC; a correlação com dois volumes também é coberta no serviço outbound, sem avisos de segurança de tipos no Java. O JAR foi recompilado e o processo PM2 foi reiniciado antes da validação HTTPS externa.
- Erros e contingência de cotação Intelipost (peso/CEP/dimensões e tabelas Fallback Tables V2) pertencem à plataforma/ERP de cotação. O Satélite não tem os dados nem endpoint para cotar e não deve calcular frete ou carregar tabelas de fallback no escopo SELIA de rastreamento.
- DTOs REST próprios devem preferir `record`, camelCase no Java e `@JsonProperty` para nomes externos divergentes; DTO raiz deve tolerar campos desconhecidos.
- Fluxos que transformam coleções potencialmente anuláveis devem validar explicitamente os elementos antes de acessar métodos ou campos, mantendo a análise de nulidade da IDE ativa sem avisos mascarados.
- Qualquer alteração de banco deve ser script SQL versionado e idempotente; a atualização operacional esperada é executar `database/subir_database.bat`.
- Registros de auditoria e logs devem preservar rastreabilidade histórica; limpezas físicas só podem existir como política técnica excepcional, documentada, versionada, idempotente e restrita ao banco `SATELITE_TMS_AUDITORIA`.

## Protocolo de Planejamento de Requisições
- Antes de iniciar qualquer planejamento ou escrita de código, a IA DEVE OBRIGATORIAMENTE ler `AGENTS.md` do projeto local e `CONTEXTO_GLOBAL.md` quando presente no workspace.
- O `CONTEXTO_GLOBAL.md` dita as regras do ecossistema e o `AGENTS.md` dita as regras locais. Falhar em ler e aplicar essas regras resulta em quebra arquitetural.
- Ao receber uma nova requisição para este projeto, atuar como Arquiteto de Software e usar este `states.md` como ESTADO ATUAL.
- A análise deve respeitar a stack, a arquitetura, as fronteiras de banco e os contratos de dados descritos neste arquivo.
- A resposta de planejamento deve retornar somente o bloco `## Tarefas Pendentes`, formatado em Markdown.
- O bloco deve decompor a requisição em tarefas sequenciais, lógicas e granulares, especificando arquivos exatos, variáveis, tipagens e validações que deverão ser alterados ou criados.
- É proibido incluir saudações, conclusões, explicações fora dos bullets ou reescrever outras seções durante a resposta de planejamento.

## Tarefas Pendentes

- [ ] Enviar à SELIA a URL já homologada tecnicamente `https://satelite-api.rodogarcia.com.br/api/selia/intelipost/pre-shipment-list`, pedir o cadastro e aguardar uma PLP real de homologação. Manter `APP_SELIA_ENABLED=false` nesta etapa.
- [ ] Confirmar com SELIA/Intelipost que o identificador técnico numérico retornado em `logistics_provider_shipment_list` é aceito no contrato de PLP. Caso exijam um número operacional próprio, receber a regra e alterar somente esse mapeamento antes de aceitar pedidos produtivos.
- [ ] Receber uma PLP real, validar o aceite/eco integral de pedido e volume e conferir a correlação técnica criada na auditoria, sem expor dados do pedido ou da NF-e em documentação ou logs.
- [ ] Obter o código DePara de entrega realizada para `SELIA_INTELIPOST_DELIVERY_EVENT_CODE` e uma NF-e de homologação com ocorrência ESL, CT-e e comprovante reais. Confirmar, sem inferência, o pedido/volume retornado pela PLP ou pela ESL.
- [ ] Ativar temporariamente `SELIA_NFE_WHITELIST_ENABLED=true`, preencher `SELIA_NFE_WHITELIST` exclusivamente com a NF-e de homologação e executar um único POST AddEvents autorizado. Confirmar o vínculo na Intelipost, a auditoria e a idempotência.
- [ ] Após o AddEvents aprovado, habilitar `APP_SELIA_ENABLED=true`; a ampliação ou remoção da whitelist será decisão operacional posterior.
- [ ] Opcionalmente, solicitar à Intelipost o escopo de leitura para `GET /shipment_order/invoice_key/{chave_nfe}` e `GET /shipment_order/get_volumes/{pedido_envio}`. Não incorporar essas consultas enquanto retornarem HTTP 401; elas não bloqueiam mais a homologação baseada na PLP.
