# Integrações: resultados, pendências e reprocessamento

Diagnóstico com corte às 21:23 BRT e implementação autorizada em 21/09/2026. Escopo: Satélite TMS API e Dashboards. Foram lidos os AGENTS/STATES pertinentes e o contexto compartilhado referenciado por eles. A implementação e as validações estão registradas no fechamento deste documento; as seções de diagnóstico preservam a evidência anterior à mudança.

## Evidência do problema

A API local devolveu os mesmos números da captura: 1.082 XMLs/dados e 1.126 comprovantes confirmados em setembro, 169 etapas pendentes e 283 bloqueadas. Em 21/09, são 19 XMLs confirmados e 160 com último estado de erro. As outras duas falhas XML do período aparecem em 14/09 e 16/09.

A SQL com a mesma precedência de sucesso e deduplicação XML do contrato v2 decompôs os 160 de 21/09:

| Último motivo registrado | CT-es distintos | Com contador de avaliações maior que um |
| --- | ---: | ---: |
| Acesso XML recusado, HTTP 401 | 17 | 7 |
| Espera de autenticação da origem | 143 | 76 |
| Total | 160 | 83 |

Os contadores chegam a 43 avaliações, mas incluem retenções sem transmissão. Os 17 representam documentos cujo último motivo é HTTP 401; não medem todas as chamadas HTTP recusadas naquele dia. Os 143 não representam envios recusados pela Vedacit. A primeira avaliação local tampouco prova que o documento seja novo na operação.

Saldo bruto dos comprovantes SFTP: 205 registros bloqueados na origem, dez bloqueados no destino, 67 timeouts ambíguos, um pendente normal e três pendentes técnicos. Neste corte, 205 + 10 = 215 explica a coluna de bloqueios do ciclo; 215 + 67 = 282 coincide com o indicador de comprovantes bloqueados. O indicador soma ainda uma etapa XML bloqueada para chegar a 283. Essa igualdade foi observada neste corte: o worker conta registros e o indicador deduplica etapas; não pressupor igualdade em qualquer base. Também não somar XML e comprovante como notas distintas.

O ciclo 1326, finalizado às 17:40:59, contém sete XMLs confirmados, sete comprovantes enviados, cinco avaliações XML sem conclusão, 18 já processados e zero erros de comprovante. Mesmo assim, o resultado é FALHA/XML_FALHAS_MISTAS. O ciclo 1330, às 20:38:12, confirma dois de cada etapa e também fica FALHA. O ciclo aberto na captura terminou às 21:19:46, com 12 avaliações XML sem conclusão e zero erros de comprovante. O motivo misto não permite recuperar as causas individuais antigas a partir do saldo atual.

## Causas no código

1. [IntegracaoIndicadoresEtapasRepository.java](../backend/java/src/main/perfis/api/java/com/example/satelite/repositories/IntegracaoIndicadoresEtapasRepository.java) consulta o **estado atual** de `tb_log_integracao`, deduplica e usa `data_processamento_dados`/`data_processamento_canhoto` nas séries. Não é um histórico imutável de eventos. A própria classe declara que não reconstitui tentativas sobrescritas. Sucesso tem precedência; uma confirmação posterior retira o documento das falhas atuais.
2. [EtlEstadoIntegracaoService.java](../backend/java/src/main/java/com/example/satelite/services/etl/EtlEstadoIntegracaoService.java), em `aplicarResultadoIntegracao` e `deveAtualizarDataProcessamento`, atualiza a data e incrementa o contador quando o novo estado é ERRO_DESTINO, inclusive se o estado anterior já era esse. Assim, reavaliações deslocam a falha para a data mais recente. O contador não distingue envio SOAP, consulta da origem e espera local.
3. [ResultadoRegistro.java](../backend/java/src/main/java/com/example/satelite/services/etl/ResultadoRegistro.java) considera RETIDO e RETIDO_ACESSO_ORIGEM como erro. [ResultadoDestino.java](../backend/java/src/main/java/com/example/satelite/services/etl/ResultadoDestino.java) agrega essas ocorrências em `erros`; a causa XML_FALHAS_MISTAS combina acesso com outros erros **ou retenções**.
4. [WorkSftpClientesRunner.java](../backend/java/src/main/java/com/example/satelite/services/etl/WorkSftpClientesRunner.java) marca falha ao haver erro XML e, no fechamento, quando existe `motivo`. Isso mistura encerramento do processo, impedimento documental e falha técnica. Os sucessos são preservados numericamente, mas o título geral dá a impressão de perda integral.
5. No Dashboard, [CiclosIntegracaoPanel.tsx](../../etl-dash/dashboards/frontend/src/components/domain/integracoes/CiclosIntegracaoPanel.tsx) só ameniza XML_RETIDO/XML_ACESSO_ORIGEM sob condições específicas. XML_FALHAS_MISTAS continua vermelho, mesmo com zero erro de comprovante. [IndicadoresIntegracoesPanel.tsx](../../etl-dash/dashboards/frontend/src/components/domain/integracoes/IndicadoresIntegracoesPanel.tsx) desenha `falhas` da API como uma série diária ao lado das confirmações. Não há deslocamento feito pelo React.

Há ainda uma inconsistência semântica de fila: para XML, ERRO_DESTINO é classificado como pendente, enquanto PENDENTE_ORIGEM é bloqueado. Reavaliar uma recusa de acesso pode mudar a coluna do documento sem resolver o impedimento. A classificação XML precisa conhecer a causa operacional, como já ocorre parcialmente com comprovantes.

## Organização proposta para a tela

Separar três perguntas: quanto foi confirmado, o que ainda aguarda solução e como executou cada ciclo.

- **Produção no período:** manter XML/dados e comprovantes confirmados com suas primeiras datas confiáveis. Os gráficos principais mostram confirmações; recuperados por reprocessamento podem ser um subconjunto identificado quando houver evento confiável, sem duplicar o total. Preservar o tratamento dos 702 comprovantes com primeira data incerta.
- **Pendências atuais:** apresentar documentos/etapas distintos por ação: aguardando processamento, nova tentativa programada, aguardando origem, aguardando confirmação do destino e intervenção necessária. Mostrar motivo, idade conhecida da pendência, última avaliação e próxima tentativa quando registrada. Os saldos continuam explicitamente de todas as datas.
- **Execuções:** informar andamento/finalização/interrupção separadamente dos resultados por etapa. Confirmações aparecem primeiro; pendências recorrentes ficam em detalhe próprio, com os erros técnicos identificados. Um ciclo sem item elegível aparece como finalizado sem novos envios, sem sugerir pane ou sucesso documental.
- **Auditoria de tentativas:** visão secundária por data real do evento, com primeira tentativa, reprocessamento, consulta da origem e avaliação sem envio. Os valores dessa visão nunca se somam aos documentos confirmados como se formassem uma população única.

Exemplo de apresentação proposta para o ciclo das 17:40:

> Ciclo finalizado · com pendências documentais  
> XML: 7 confirmados · Comprovantes: 7 enviados  
> XML: 5 avaliações sem conclusão · 18 já processados  
> Saldo após o ciclo: 215 bloqueios · 67 aguardando confirmação

As cinco avaliações continuam com a causa histórica disponível, sem convertê-las por suposição em cinco falhas transitórias. Erros técnicos, recusas e impedimentos que exigem ação permanecem visíveis. Cor deve acompanhar a ação necessária; sucesso parcial não elimina falha real.

## Política proposta de reprocessamento

| Situação | Tratamento |
| --- | --- |
| Confirmação existente | Preservar aceite, identidade e primeira data; impedir reenvio. |
| Falha transitória com resultado conhecido | Nova tentativa limitada, com intervalo e horário persistido; processar somente a etapa pendente. |
| Arquivo/metadado ausente na origem | Aguardar disponibilidade; reavaliar após intervalo ou evidência nova. Não consumir tentativa de envio sem chamar o destino. |
| Acesso 401/403 | Suspender novas consultas à fonte afetada durante a espera; manter uma causa compartilhada e revalidação controlada. Revisitas retidas não viram novos erros de envio. |
| Timeout ou queda após início do envio | Consultar/reconciliar o destino antes de liberar nova transmissão; resposta desconhecida não é recusa nem sucesso. |
| Recusa de cadastro/documento ou identidade inválida | Exigir correção e revalidar elegibilidade; não repetir automaticamente sem alteração relevante. |
| Sucesso histórico sem data confiável | Conferir a evidência; não reenviar para fabricar uma confirmação datada. |

O Satélite já possui fila normal seguida de técnica, cooldown XML, locks e proteção de aceites/timeouts. Evoluir essas rotinas, incluindo a reconciliação noturna existente, sem criar um segundo fluxo de envio. Antes de alterar limites/contadores de controle, rastrear todos os consumidores: mudar `ResultadoRegistro.erro()` isoladamente também afeta cursor, suspensão da passagem e seleção de retry.

## Trabalho no Satélite

1. Separar resultado do ciclo, resultado documental e decisão de próxima ação em contratos tipados. Persistir causas e contagens por etapa/ciclo, incluindo retenções, tentativas efetivamente iniciadas e avaliações sem envio. Pontos: `ResultadoRegistro`, `ResultadoPagina`, `ResultadoDestino`, `EtlRegistroService`, `EtlEstadoIntegracaoService`, `EtlRepescagemService`, `WorkSftpClientesRunner`, `WorkSftpClientesAuditoriaRepository` e `WorkSftpClienteStatusDTO`.
2. Registrar eventos de auditoria por documento/etapa/ciclo sem sobrescrever ocorrências anteriores. Distinguir primeira pendência observada, tentativa iniciada, resultado recebido e confirmação. Garantir idempotência de gravação, associação ao par/CT-e correto e proteção após interrupção. Referenciar identidade técnica; não guardar XML/imagem. Preservar aceites duráveis existentes.
3. Versionar SQL aditiva/idempotente exclusivamente em SATELITE_TMS_AUDITORIA, com baseline e modo restrito correspondente em `database/subir_database.bat`. Reaproveitar auditorias existentes onde bastarem; o log mutável atual sozinho não sustenta histórico completo de tentativas.
4. Evoluir `IntegracaoIndicadoresEtapasRepository`, `IndicadoresEtapasDTO`, serviço/controller e filtros de ciclos. Publicar contrato novo para separar confirmações, saldo atual e eventos por dia; manter v2 enquanto houver consumidores antigos. Fazer agregações e paginação no SQL.
5. Aplicar a política tipada de elegibilidade/espera aos fluxos existentes; registrar próxima avaliação e causa sem alterar silenciosamente os limites legados. Disponibilidade da origem, timeout e intervenção não podem ser inferidos apenas pelo nome ERRO_DESTINO.

## Trabalho no Dashboard

1. Atualizar `frontend/src/api/endpoints/integracoesEtapas.ts` e `integracoesServico.ts` para os contratos novos; validar campos e cobertura histórica. Em `backend/.../IntegracaoSateliteClient.java`, `IntegracoesService.java` e controller, encaminhar rotas/filtros adicionais preservando autenticação e proxy. A regra de retry pertence ao Satélite; não exige migração operacional em DASHBOARDS nem acesso ao ETL.
2. Ajustar `IndicadoresIntegracoesPanel.tsx` e `integracoesEtapas.ts`: confirmações por dia e saldo atual separados. Retirar da comparação principal a série de último estado apresentada como se fosse histórico de erros; manter acesso explícito à auditoria. Não trocar a série por zeros nem subtrair constantes.
3. Ajustar `PendenciasEtapasPanel.tsx` com categorias acionáveis e auditoria filtrável; distinguir número de etapas de número de notas e causas compartilhadas.
4. Ajustar `CiclosIntegracaoPanel.tsx`, seus filtros e rótulos para exibir resultado parcial e execução separadamente. Metadados de inventário e saldo são contagens naquele instante, não novas ocorrências do ciclo. Contratos antigos mantêm a informação disponível sem classificação inventada; falhas técnicas comprovadas preservam destaque.
5. Sincronizar `kpiDictionary.ts`, `chartDictionary.ts`, tipagens e testes na mesma entrega. Validar desktop/celular, temas claro/escuro e ausência de aumento da altura padrão dos gráficos.

## Critérios de aceite e limites históricos

- Um documento avaliado dez vezes continua uma pendência atual, com dez avaliações auditadas e apenas as transmissões reais contadas como tentativas de envio.
- Uma falha de ontem reavaliada hoje não muda a data do evento de ontem. Após confirmação, a pendência atual fecha, o sucesso é contado uma vez e o evento antigo permanece consultável.
- Sete confirmações e cinco retenções preservam ambos os resultados; não representam falha integral nem autorização para reenviar os sete.
- Testar espera de acesso sem novo request, timeout protegido, falha de infraestrutura, ciclo interrompido, contrato antigo, duplicação de NF-e/CT-e e confirmação anterior inclusive arquivada/sem data.
- Conferir igualdade entre cards e séries da mesma métrica, com fuso America/Sao_Paulo e filtros iguais. Testes Satélite pelo script isolado com rede bloqueada; frontend com testes pertinentes, TypeScript, lint e build; proxy backend se alterado.
- Começar o histórico detalhado na data de implantação. Ciclos antigos preservam seus agregados; dados sobrescritos não podem ser reconstruídos integralmente pelo último estado. Eventual recuperação histórica exige evidência individual e cobertura explicitada, nunca data inventada ou zero para período não medido.

## Entrega inicial da investigação, antes da autorização de implementação

SQL reproduzível: [20260921_indicadores_falhas_retentativas.sql](../database/sql/diagnostico/20260921_indicadores_falhas_retentativas.sql), executada com rollback, 21 linhas agregadas/de ciclos. Capturas locais em `target/diagnostico-painel-20260921/`: `sql.json`, `indicadores.json`, `ciclos.json` e `captura.json`. O relatório preserva as conclusões mesmo sem esses artefatos ignorados pelo Git.

Validação às 21:27: 162 falhas XML do período iguais entre SQL/API, 160 no dia e 83 reavaliados; somas das séries iguais aos resumos de confirmações/falhas nas duas etapas. Links locais conferidos e `git diff --check` sem erro nos dois repositórios. Resultado em `target/diagnostico-painel-20260921/validacao.json`. Não foi executada suíte de aplicação porque não houve alteração funcional.

Não houve alteração de Java/React, dados operacionais, configuração, JAR ou runtime. Nenhuma chamada ESL/SOAP ou reprocessamento foi provocado. As alterações anteriores já existentes no Dashboard foram preservadas. Os próximos blocos acima são proposta de implementação, registrados também nos STATES dos dois projetos.

## Implementação concluída em 21/09/2026

O contrato v3, em `indicadores-operacionais`, separa confirmações por primeira data confiável, saldo deduplicado por próxima ação e eventos na data em que ocorreram. `pendencias-etapas` fornece documentos e horários conhecidos com filtros e paginação SQL. O v2 continua disponível. A precedência de aceites e os 702 comprovantes sem primeira data confiável foram preservados; nenhuma data histórica foi fabricada.

As seis categorias exclusivas são processamento, nova tentativa, origem, confirmação, intervenção e conferência. As três séries principais da UI mostram confirmações; o saldo é atual, de todas as datas. As quantidades abrem os documentos. Avaliações/tentativas ficam em painel secundário recolhível; os ciclos mostram confirmações primeiro e expõem as ocorrências em detalhe. O resultado novo do ciclo distingue finalização com pendências de falha técnica/interrupção, mantendo o status legado e o exit code usados pelos controles do worker. Histórico sem evidência suficiente recebe `NAO_CLASSIFICADO`, sem causa inferida. Esperas XML conhecidas em ciclos antigos com conexão OK são reconhecidas pelo motivo persistido.

`AcaoEtapaIntegracao` classifica a ação e persiste a próxima elegibilidade. Cooldown XML reutiliza `VEDACIT_XML_SOURCE_RETRY_COOLDOWN_MS`; documentos usam `INTEGRATION_DOCUMENT_RETRY_COOLDOWN_MS`, ambos 30 minutos por padrão, com limite de 1 segundo a 24 horas. As filas existentes e a releitura sob lock respeitam a agenda. A pausa por 401/403 passa a consultar evidência persistida, sobrevivendo à troca da JVM. Revisitas sem envio têm avaliação própria, sem mover a data do erro nem estender a agenda. As proteções anteriores de aceite, par NF-e/CT-e e timeout continuam prevalecendo. A mudança não libera recusas ou resultados desconhecidos para reenvio automático.

A V28 e seu baseline idêntico adicionam seis campos técnicos, o resultado de execução e `tb_integracao_etapa_evento`, com UUID único, índices e trigger de imutabilidade. A mesma avaliação pode ser salva de novo sem duplicar seu evento. Avaliações com resultado, consultas de XML à origem e inícios de transmissão são distintos; o contexto do ciclo atravessa o executor SOAP. Transmissões e consultas instrumentadas abrangem Vedacit. Revisitas a itens já processados permanecem no agregado do ciclo e não são novas tentativas de envio. Repetições de envio são um subconjunto do total, computadas considerando o primeiro evento disponível inclusive anterior ao filtro de período.

A migration foi validada duas vezes com rollback e **aplicada** somente em `SATELITE_TMS_AUDITORIA`, via autenticação Windows do operador. A credencial da aplicação passou nas leituras, na inserção idempotente com rollback e na prova de imutabilidade (erro esperado 51028). Não houve saneamento de registros antigos. O modo restrito é `database/subir_database.bat --eventos-etapas`; não é necessário reaplicar o setup completo.

## Evidências finais

| Verificação | Resultado |
| --- | --- |
| Satélite, JDK 17, JVM com rede bloqueada | 144 testes, zero falhas/erros; `target/unit-tests/coverage-20260921-222111-935/summary.json`. |
| SQL Server, repositórios JDBC reais | 83 consultas em leitura/rollback; 36 categorias com resumo e detalhe concordantes; filtros de ciclos válidos. `target/diagnostico-painel-20260921/probe-v3.json`. |
| SQL Server, seletores JPA reais | 44 consultas em leitura/rollback; 3.501 NF-es; recuperação XML, seleção normal/técnica, limites e deduplicação conferidos. Máximo 503 parâmetros; `probe-selecao.json`. |
| Confirmações | 1.082 XMLs e 1.126 comprovantes, iguais ao contrato anterior e às séries diárias. 160 esperas de acesso da captura pertencem a origem; o saldo total de XML aguardando origem é 162. |
| Dashboard backend | 704 testes, zero falhas/erros; Maven verify com pisos JaCoCo aprovados. |
| Dashboard frontend | 392 testes/61 arquivos aprovados, TypeScript, lint, encoding, ambiente de produção e Vite aprovados. Dois testes novos foram corrigidos para distinguir opções do filtro dos badges antes da rodada final. |
| Navegador | Componentes reais e CSS do build, Edge isolado com transporte simulado; 1600 e 390 px, claro/escuro, três gráficos de 350 px, detalhe funcional, sem transbordamento ou exceções. |

As evidências do Dashboard ficam em `.tmp/quality/20260921-221425-f79263/` e `frontend/.tmp/quality-build/20260921-221425-f79263/`. O relatório inicial guarda as falhas dos testes novos; `testes-finais.log` comprova a rodada corrigida. Prévias e medição visual em `frontend/.tmp/integracoes-v3/`. Agregados da prévia vieram da consulta de leitura; o ciclo com sete envios e o documento do detalhe são demonstrativos. As sondas não inicializam Spring nem integrações e usam somente a database permitida.

## Pacotes e ativação

- Satélite: `target/unit-tests/coverage-20260921-222111-935/satelite-0.0.1-SNAPSHOT.jar`, SHA-256 `B4389CC8C8E34C8B32E81497BDEAAA0FB38E85663CF0E9BA71ECCE8EAE6179D1`, gerado das classes testadas, incluindo os WSDLs locais.
- Dashboard API: `backend/target/quality/20260921-221425-f79263/dashboard-api-1.0.0.jar`, SHA-256 `F3D0A5234ADB28218398CD275C3627C8D0F6946389576DD79C07234A44F33762`.
- Dashboard UI: `frontend/.tmp/quality-build/20260921-221425-f79263/dist`, build final após correção dos testes e precedência do resultado novo sobre o fallback antigo.

**Os pacotes são candidatos; não foram instalados nem reiniciados os serviços produtivos.** O JAR operacional Satélite `EFF36F93...`, o JAR Dashboard `A7F31774...`, o `dist-prod` e configurações foram preservados. A ordem de ativação é schema V28 (já preparado), Satélite API/worker, Dashboard API e UI. O operador humano administra os processos, inclusive `iniciar-prod.bat`. O histórico de eventos começa quando a versão nova executar; a tabela não foi preenchida artificialmente com o último estado de logs antigos. A validação não enviou documentos reais nem consultou ESL/SOAP.
