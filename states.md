# Estado Atual do Sistema

## Stack Tecnológica
- Java 17 com Spring Boot 4.1.0 e Maven Wrapper.
- Spring Cloud OpenFeign para integrações REST externas.
- Spring Web MVC para endpoints auxiliares de auditoria e quarentena.
- Spring Web Services, Jakarta XML Web Services API, JAX-WS RI e `jaxws-maven-plugin` para clientes SOAP da Vedacit/MultiTMS gerados a partir de WSDL.
- Spring Data JPA/Hibernate com Microsoft JDBC Driver para SQL Server.
- Banco SQL Server exclusivo `SATELITE_TMS_AUDITORIA`/`satelite_tms_auditoria` para auditoria, cursores e estado técnico da integração.
- Apache PDFBox e Java ImageIO/AWT para renderização, conversão, compressão e normalização de canhotos.
- Lombok em entidades/modelos internos; testes com starters de teste Spring Boot para JPA, Web MVC e Web Services.
- Operação em Windows por `satelite.bat`, `build.bat` e scripts em `scripts/`; banco provisionado por `database/subir_database.bat`.

## Arquitetura e Padrões
- Aplicação middleware/ETL headless: o fluxo principal é acionado por Spring Scheduling em `OrquestradorEtlScheduler`, não por controller web.
- Arquitetura em camadas: `clients` concentra OpenFeign REST; `dto.rodogarcia` modela entrada ESL/Rodogarcia; `dto.ppg` modela saída PPG/OK Entrega; `services.etl`, `services.ppg`, `services.vedacit` concentram regras por domínio; `repositories` acessa auditoria SQL; `models` contém entidades JPA; `utils` guarda lógica pura de imagem/download.
- `SateliteApplication` habilita OpenFeign; `SchedulerConfig` habilita agendamento.
- `OrquestradorEtlService` coordena os destinos PPG e Vedacit, respeitando toggles `APP_PPG_ENABLED`, `APP_VEDACIT_ENABLED`, `APP_SCHEDULER_ENABLED`, modo ciclo único e modo retroativo.
- `EtlFluxoDestinoService` encapsula paginação por destino, cursor, whitelists, filtro de ocorrência, detecção de loop de cursor, circuit breaker por falhas de infraestrutura e avanço seguro do cursor.
- `EtlRegistroService` aplica idempotência por log existente, processa pendências de canhoto, baixa comprovantes da ESL e atualiza o estado por registro.
- `EtlEstadoIntegracaoService` centraliza status, tentativas, datas de processamento e conversão para resultado operacional.
- `EtlResilienciaService` encapsula retries por erro transitório, backoff e limite de tentativas.
- `EslRequestPolicyService` impõe intervalo mínimo entre chamadas ESL, trata HTTP 429/5xx e falhas de transporte como transitórias e controla cooldown para consultas por período.
- Banco versionado por scripts SQL idempotentes em `database/sql/schema` e `database/sql/migration`; todo script deve usar `USE`, `SET ANSI_NULLS ON` e `SET QUOTED_IDENTIFIER ON`.
- Classes SOAP da Vedacit são geradas em `target/generated-sources/wsimport*`; não devem ser editadas manualmente.
- Logs, auditorias, cursores, quarentenas e estados técnicos de integração usam exclusão lógica obrigatória quando precisarem sair das leituras operacionais. Hard delete/`DELETE FROM`/`TRUNCATE` em rotinas comuns é proibido; use status, `ativo`, `deleted_at`, `arquivado` ou campo equivalente, com filtros explícitos nas consultas de produção.

## Fluxo de Dados e Integrações
- Origem principal: Rodogarcia/ESL Cloud via REST OpenFeign em `RodogarciaClient`.
- Endpoint ESL de ocorrências: `GET ${RODOGARCIA_API_BASE_URL}${RODOGARCIA_CUSTOMER_OCCURRENCES_PATH}`, padrão `/api/customer/invoice_occurrences`, com `Authorization: Bearer <token>`.
- Parâmetros usados na origem: `start` para cursor, `invoice_key` para whitelists/repescagem, `since` para retroativo e `occurrence_code=1`.
- Comprovantes ESL: `GET /api/customer/freight_delivery_receipts?cte_key=...`.
- XML de CT-e ESL: `GET ${RODOGARCIA_CTE_XML_PATH:/api/ctes}?key=...`, usado quando `VEDACIT_SEND_CTE_XML_ENABLED=true` e com token `RODOGARCIA_MASTER_API_REST`.
- Destinos ativos: `PPG` com token ESL próprio `RODOGARCIA_TOKEN_PPG` e `VEDACIT` com token ESL próprio `RODOGARCIA_TOKEN_VEDACIT`; cada destino mantém cursor independente.
- Destino PPG/OK Entrega: `PpgClient` faz login em `/assets/ws/ws.0.loginapp.php` e envia ocorrência em `/assets/ws/ws.0.ocorrenciaentregacache_api.php`; `PpgAuthService` mantém token em memória por 13 dias.
- Destino Vedacit/MultiTMS: SOAP em `Ocorrencias.svc`, `NFe.svc` e `CTe.svc`, com token em header SOAP e timeouts configuráveis.
- Canhotos PPG: imagem baixada da ESL, convertida para RGB/JPEG, recortada, redimensionada para `1536x240`, gravada a 150 DPI e enviada com prefixo `data:image/jpeg;base64,`.
- Canhotos Vedacit: imagem ou primeira página de PDF é convertida/comprimida em JPEG, limite máximo de 400 KB, Base64 bruto sem prefixo MIME.
- Persistência técnica: `dbo.tb_log_integracao` registra ocorrência, chave NFe, frete, cursor, status geral, status de dados, status de canhoto, tentativas, mensagens de erro, payloads e referência de canhoto; `dbo.tb_controle_cursor` armazena cursor por `sistema_destino`.
- Endpoints auxiliares expostos: `/api/auditoria/integracoes-clientes`, `/api/auditoria/integracoes-clientes/evolucao-diaria`, `/api/auditoria/logs/{id}/imagem`, `/api/etl/quarentena/erros` e `/api/etl/quarentena/{destino}/reprocessar`.

## Regras de Negócio Consolidadas
- O fluxo principal só deve processar entrega realizada: `occurrence.code == 1`. Outros códigos são ignorados e auditados.
- Controllers não devem acionar o fluxo principal de integração; endpoints web existem apenas para auditoria, imagem de canhoto e quarentena/reprocessamento manual.
- Ações SQL manuais ou automatizadas devem atingir exclusivamente `SATELITE_TMS_AUDITORIA`; é proibido tocar `ETL_SISTEMA`, `DASHBOARDS` ou qualquer outra database.
- O banco guarda apenas auditoria, status, cursor e erros. Não persistir fisicamente NF completa, imagem de canhoto ou massa operacional de domínio.
- O cursor só avança depois do processamento/auditoria da página. Em erro de registro no modo incremental, o cursor não avança para permitir retry.
- Em carga retroativa, erros por registro são registrados e a paginação pode avançar em memória para não travar o histórico.
- Cada destino tem whitelists independentes de NF-e: `PPG_NFE_WHITELIST_ENABLED/PPG_NFE_WHITELIST` e `VEDACIT_NFE_WHITELIST_ENABLED/VEDACIT_NFE_WHITELIST`.
- Registros com status final `ENVIADO` ou `IGNORADO` não são reenviados automaticamente.
- Status consolidados atuais: `RECEBIDO`, `IGNORADO`, `ENVIADO`, `PARCIAL`, `PENDENTE_FOTO`, `ERRO_DESTINO`, `SUCESSO` e `NAO_APLICAVEL`.
- PPG exige canhoto disponível; sem imagem, o registro fica `PENDENTE_FOTO` e não envia payload.
- Vedacit pode concluir dados e canhoto separadamente; se canhoto faltar, o registro pode ficar parcial com `status_canhoto=PENDENTE_FOTO`.
- Duplicidade retornada por PPG ou Vedacit é conciliada como sucesso, não como erro fatal.
- Erros HTTP transitórios ou de infraestrutura incluem 429, 500, 502, 503, 504, timeouts e falhas de transporte; há backoff e limite de tentativas antes de quarentena/bloqueio de retry automático.
- Circuit breaker de infraestrutura abre por falhas consecutivas conforme `ETL_CIRCUIT_BREAKER_TRANSIENT_FAILURE_THRESHOLD`.
- A ESL deve respeitar intervalo mínimo entre requests (`ESL_MIN_INTERVAL_BETWEEN_REQUESTS_MS`, padrão 2000 ms) e backoff especial para 429.
- Consultas por período usam cooldown: até 30 dias sem cooldown adicional, 31 a 183 dias com 1 hora, acima disso com 12 horas.
- Toda credencial, URL e token deve vir de `.env`, `application.properties` ou variável de ambiente; não pode haver segredo hardcoded.
- DTOs REST próprios devem preferir `record`, camelCase no Java e `@JsonProperty` para nomes externos divergentes; DTO raiz deve tolerar campos desconhecidos.
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
- Nenhuma tarefa pendente registrada.
