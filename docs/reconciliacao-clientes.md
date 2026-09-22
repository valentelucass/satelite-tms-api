# Supervisor de reconciliação dos clientes

Implementado e pacote instalado em **21/09/2026 às 23:29 BRT**, com os processos do Satélite parados pelo operador. A instalação inclui também as alterações de eventos/ações operacionais de Integrações de 21/09. Nenhuma integração foi iniciada nesta entrega.

## Organização e comportamento

O [ecosystem.config.js](../ecosystem.config.js) define somente três processos do Satélite, todos no mesmo `target/satelite-0.0.1-SNAPSHOT.jar`:

| Processo | Responsabilidade |
| --- | --- |
| `Satelite-API-19090` | API de consulta e auditoria |
| `WORK-SFTP-CLIENTES` | Ciclos regulares; cadência anterior preservada |
| `RECONCILIACAO-CLIENTES` | Supervisor residente das revisões programadas |

O agendador compartilhado descobre adaptadores por cliente, valida a lista habilitada e despacha no máximo uma passagem em voo por integração. Uma thread por cliente habilitado permite que PPG/SELIA/SUPPORTE avancem enquanto a Vedacit aguarda uma resposta. Exceções e horários são isolados; tarefas que aguardaram até depois do fim da janela não começam. O fechamento do contexto interrompe as passagens. Locks SQL por cliente impedem revisões concorrentes em outra JVM; o recurso Vedacit mantém o nome anterior, preservando a exclusão com versões legadas.

O processo de reconciliação recusa coexistir na mesma JVM com API passiva, worker que encerra a JVM, schedulers antigos e runners de reenvio. API e worker recebem explicitamente `APP_RECONCILIACAO_ENABLED=false` no ecosystem.

## Regras de cada cliente

- **VEDACIT:** adaptador reutiliza os serviços existentes, tabelas V27, checkpoint, confirmação permanente, fonte SFTP exclusiva, locks por documento, proteção de timeouts e recusas, primeira data e cooldown. As regras de recuperação permanecem. Histórico e IDs de execução não foram migrados ou reiniciados.
- **PPG, SELIA e SUPPORTE:** conferência local dos registros de auditoria, com paginação, checkpoint e uma execução por cliente/data. Sucesso com data, sucesso sem data confiável, pendência, falha registrada e estado desconhecido são distinguidos. Arquivados são apenas conferidos. Não chama destinos externos, não muda estados dos documentos e não reenvia. Os clientes HTTP existentes só implementam envio; usar envio como confirmação seria incorreto.

Essa distinção segue a alternativa conservadora apresentada durante a implementação. O suporte de recuperação externa dos demais clientes depende de contratos de confirmação e critérios próprios; não foi inventado nem habilitado implicitamente. Adicionar cliente novo requer adaptador e configuração, sem criar outra JVM.

## Configuração

- `APP_RECONCILIACAO_ENABLED`: liga o supervisor (padrão false).
- `RECONCILIACAO_CLIENTES`: lista explícita; o ecosystem habilita `VEDACIT,PPG,SELIA,SUPPORTE`. Cliente desconhecido, repetido ou vazio causa erro de configuração.
- `RECONCILIACAO_POLL_MS=60000` e `RECONCILIACAO_INITIAL_DELAY_MS=1000`.
- `<CLIENTE>_RECONCILIACAO_START=02:00`, `END=06:00`, `TIME_ZONE=America/Sao_Paulo` e `PAGE_SIZE=100`. A janela exige início anterior ao fim no mesmo dia. O fuso usa `APP_TIME_ZONE` como fallback.
- O controle de recuperação Vedacit continua em `VEDACIT_RECONCILIACAO_RECOVERY_ENABLED`; ele não habilita reenvios de outros clientes.

Exemplo sem credenciais em [database/config/env.example.properties](../database/config/env.example.properties). Argumentos do ecosystem têm precedência sobre `.env`.

## Banco e consulta

A V29, também refletida no baseline `database/sql/schema/05_reconciliacao_clientes.sql`, adiciona somente `tb_reconciliacao_cliente_execucao` e `tb_reconciliacao_cliente_item`. Unicidade por cliente/data; inserção do item e avanço de checkpoint na mesma transação. O registro exige que o documento pertença ao cliente da execução. Não altera tabelas, estados ou confirmações dos documentos. O modo restrito `database/subir_database.bat --reconciliacao` aplica V27 e V29 idempotentes. **V29 já aplicada nesta entrega**, somente em `SATELITE_TMS_AUDITORIA`.

Consultas somente leitura:

- `GET /api/auditoria/reconciliacoes/{cliente}`
- `GET /api/auditoria/reconciliacoes/{cliente}/{id}/resumo`

`cliente` aceita VEDACIT, PPG, SELIA ou SUPPORTE. A rota Vedacit anterior continua disponível e lê o mesmo histórico. Os resultados dos clientes locais são diagnósticos de registros de auditoria, não novos aceites externos ou KPIs de documentos enviados.

## Validação e pacote

- Suíte completa: **504 testes, 501 aprovados, zero falhas/erros e três manuais desabilitados**; Java 17 com rede bloqueada. Saída `target/unit-tests/coverage-20260921-232250-030/`.
- Dois testes adicionais de contexto aprovados: quatro adaptadores registrados no supervisor sem disparo inicial; nenhum supervisor criado com a flag desligada. Saída `target/unit-tests/coverage-20260921-232618-978/`.
- Casos cobertos: janela/fuso por cliente, passagem única, cliente lento, falha isolada, atraso além da janela, configuração inválida, encerramento e regras de diagnóstico local; regressões Vedacit e Integrações incluídas.
- SQL Server com credencial da aplicação: 785 registros PPG em 113 páginas e 19 SELIA em três páginas; SUPPORTE sem registros. Checkpoint, retomada, ordem, unicidade diária, idempotência, resumo, isolamento entre clientes e rollback confirmados. Nenhum envio e nenhum estado documental alterado. Probe versionado em `scripts/validacao/ReconciliacaoClientesSqlProbe.java`.
- V29 ensaiada duas vezes na mesma transação com rollback antes da aplicação; baseline idêntico. Ecosystem validado com nomes únicos, JAR único e isolamento de flags.
- Os **1.295 arquivos** de aplicação/recursos do JAR coincidem com os da saída testada. O scheduler Vedacit antigo não está empacotado; o novo supervisor e os adaptadores estão presentes.
- JAR instalado: SHA-256 **`DE7E77BA3ABD46D85E7DF264F9F5E501B88BDAAC2EDA36421FC20E616586DCE2`**.
- Backup anterior EFF36F93...: `target/backup-pacotes/satelite-antes-reconciliacao-clientes-20260921-232912.jar`.
- Evidências consolidadas: `target/reconciliacao-clientes-validacao/` (`migration.json`, `sql-probe.json`, `ecosystem.json`, `pacote.json`, `instalacao.json`, `pm2.json`).

A primeira bateria identificou mocks ausentes dos repositórios de Integrações no teste de inicialização sem banco; corrigidos antes da suíte completa aprovada. Não se tratava de erro de schema em produção.

## Subida pelo operador

O antigo cadastro parado `VEDACIT-RECONCILIACAO-NOTURNA` foi removido, e o PM2 foi salvo. API e worker continuam parados; os demais processos foram preservados. `RECONCILIACAO-CLIENTES` será cadastrado e iniciado ao ler o ecosystem. Na pasta do Satélite, executar:

```bat
pm2 start ecosystem.config.js
pm2 save
```

O primeiro comando aplica os três cadastros desse arquivo; o segundo persiste a organização após a subida, conforme a [documentação do PM2](https://pm2.keymetrics.io/docs/usage/application-declaration/). Não usar o nome antigo. Não houve início/reinício de serviços nesta entrega; falta conferir a subida humana e o próximo ciclo natural. A publicação do Dashboard continua sendo uma etapa separada.
