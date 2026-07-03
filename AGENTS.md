# ⚙️ Regras Operacionais para IAs - Satélite TMS (Spring Boot ETL)

**Contexto do Sistema:** Este projeto atua como um "Robô Satélite" (Middleware/ETL) sem interface gráfica. A missão dele é extrair ocorrências de frete da origem (API REST ESL Cloud / Rodogarcia), transformar os dados e enviar para dois destinos distintos: PPG (OK Entrega - REST) e Vedacit (MultiTMS - SOAP).

Sempre que gerar código ou sugerir soluções, obedeça estritamente às diretrizes abaixo:

## 0. Garantia de Contexto Antes de Agir
* **Leitura obrigatória:** Antes de qualquer planejamento, análise ou escrita de código, leia este `AGENTS.md`, o `states.md` local e o `CONTEXTO_GLOBAL.md` do ecossistema quando presente no workspace.
* **Hierarquia de regras:** O `CONTEXTO_GLOBAL.md` dita as regras imutáveis do ecossistema; este `AGENTS.md` dita as regras locais do Satélite; o `states.md` registra o estado atual e as tarefas pendentes. Em caso de conflito, preserve a integridade arquitetural e explicite a decisão.

## 1. Topologia e Arquitetura em Camadas
Respeite a separação de responsabilidades. Não misture regras de domínio com chamadas HTTP.
* `models`: Apenas entidades JPA da tabela de auditoria (`tb_log_integracao`).
* `dto.rodogarcia`: Contratos de **entrada** (Leitura do JSON da ESL).
* `dto.ppg`: Contratos de **saída** (Envio do JSON para a OK Entrega).
* `clients`: Interfaces do **Spring Cloud OpenFeign** para chamadas REST externas. É estritamente proibido usar `RestTemplate` ou `WebClient`.
* `services`: Regras de negócio divididas por domínio (`services.etl`, `services.ppg`, `services.vedacit`). 
* `utils`: Lógicas puras isoladas (como processamento de imagem).

## 2. Padrões de Código e Imutabilidade
* **DTOs:** Use a feature `record` do Java 14+ para todos os DTOs nativos.
* **Mapeamento JSON:** Variáveis no Java devem estar em `camelCase`. Para campos fora do padrão vindos da API, use `@JsonProperty("nome_do_campo")`. 
* **Resiliência de Contrato:** Adicione `@JsonIgnoreProperties(ignoreUnknown = true)` em todo DTO raiz para evitar quebras se a origem adicionar novos campos.
* **Zero Hardcode:** Nenhuma credencial, URL ou ID fixo deve estar no código. Tudo deve ser injetado via `@Value` a partir do `application.properties` / `.env`.

## 3. Especificidades dos Destinos
* **Destino PPG (REST):** * A API exige um Token (LoopBack) de 14 dias. A gestão do token deve ser em memória para evitar requests desnecessários de login.
  * A imagem do canhoto exige transformação binária estrita: JPEG, sem canal alfa (fundo branco), proporção exata de 1536x240 pixels e prefixo `data:image/jpeg;base64,`.
* **Destino Vedacit (SOAP):** * É **terminantemente proibido** criar ou alterar classes/DTOs manualmente para este domínio. 
  * As classes devem ser auto-geradas via `jaxws-maven-plugin` na pasta `/target/generated-sources/wsimport/` lendo o WSDL oficial.

## 4. Comportamento ETL e Banco de Dados
* **Headless (Sem Web):** O motor do sistema é acionado via `@Scheduled`. Não crie `@RestController` para o fluxo principal.
* **Filtro de Negócio:** O orquestrador só deve repassar para os destinos os registros que possuam `occurrence.code == 1` (Entrega Realizada).
* **Database Permitida:** Toda ação manual, script, teste ou automação que toque SQL Server deve usar exclusivamente a base deste repositório: `SATELITE_TMS_AUDITORIA` (`satelite_tms_auditoria`). É proibido executar `TRUNCATE`, `DROP`, migração, carga, limpeza ou qualquer DDL/DML em `ETL_SISTEMA` ou em qualquer outra database.
* **Banco de Dados (Auditoria):** O banco de dados é apenas para log e rastreabilidade (`tb_log_integracao`). Nenhuma NF, imagem ou dados de domínio devem ser persistidos fisicamente no banco.
* **🚨 Exclusão Lógica (Soft Delete Obrigatório):** É ESTRITAMENTE PROIBIDO apagar fisicamente logs, auditorias, cursores, quarentenas ou estados técnicos de integração por hard delete (`DELETE FROM`) ou `TRUNCATE` em rotinas comuns. Correções operacionais devem preservar rastreabilidade via status, flags como `ativo = 0`, `deleted_at`, `arquivado = 1` ou campos equivalentes; leituras de produção devem filtrar registros inativos quando aplicável.
* **Tratamento de Exceções:** Falhas em integrações HTTP (4xx, 5xx) ou conversões de uma NF não devem derrubar o lote inteiro. Use blocos `try/catch` individualizados por registro e grave o resultado (ex: `ERRO_VALIDACAO` ou `ERRO_DESTINO`) na tabela de log.

## 5. Regras de Banco de Dados e Migrations
* Qualquer modificação no banco de dados DEVE ser feita através de scripts `.sql` versionados nas pastas apropriadas.
* Todo script SQL DEVE iniciar com `SET ANSI_NULLS ON;` e `SET QUOTED_IDENTIFIER ON;` após o comando `USE` para evitar falhas com o `sqlcmd`.
* Os scripts DEVEM ser 100% idempotentes, permitindo múltiplas execuções sem falhas.
* A única ação requerida pelo usuário para atualizar o banco de dados deve ser executar o arquivo `subir_database.bat`.

## Diretrizes de Sincronização de Estado (states.md)
1. Antes de iniciar a implementação de qualquer código, você DEVE ler o arquivo `states.md` para compreender o contexto arquitetural e as regras de negócio vigentes, garantindo que as novas implementações não quebrem o estado atual.
2. Leia a seção "Tarefas Pendentes" no `states.md` para entender o escopo exato do que precisa ser desenvolvido.
3. Após finalizar a escrita e modificação do código, você DEVE atualizar o arquivo `states.md`.
4. A atualização consiste em: remover a tarefa concluída da seção "Tarefas Pendentes" e atualizar as seções "Arquitetura e Padrões", "Fluxo de Dados" ou "Regras de Negócio Consolidadas" refletindo exatamente o novo estado do sistema.
5. NUNCA entregue ou finalize uma modificação de código sem antes reescrever e atualizar o `states.md` para refletir o presente.
