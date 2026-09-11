# Correção do rodízio e da auditoria — 11/09/2026

Conferência de indicadores às 17:22 BRT: a leitura avançou para 22 XMLs confirmados e manteve 14 com falha. SQL confirmou grupos distintos: um CT-e com HTTP 401 de download ESL e 13 CT-es em espera de autenticação (17 registros, desduplicados). Nenhum dos CT-es em falha tinha sucesso datado. Os logs mostram aceites XML obtidos pelo SFTP e turnos de dez comprovantes intercalados; o cursor avançou até 306222037. O total diário de comprovantes chegou a 300. O ciclo permanece em andamento. O rótulo genérico “com falha” precisa distinguir obtenção/espera na origem de recusa do destino; tarefa registrada no estado. Evidências: `target/conferencia-14-falhas-20260911/`.

Aceite de inicialização às 17:15 BRT: após start humano às 17:12, API PID 2720 e worker PID 10736 estão online, sem novos reinícios, executando o hash publicado. A API respondeu HTTP 200 com os novos campos XML. O worker iniciou a recuperação específica, recebeu HTTP 401 da origem e passou a registrar espera de autenticação, demonstrando o cooldown novo. O primeiro ciclo continua em andamento; o histórico ainda mostra o ciclo anterior. Evidências em `target/aceite-start-20260911/`. Nenhum controle de processo ou chamada às integrações foi executado pela IA nesta conferência.

Atualização de publicação em 11/09 às 17:10 BRT: JAR validado instalado em `target/satelite-0.0.1-SNAPSHOT.jar` e V22 aplicada/conferida em `SATELITE_TMS_AUDITORIA`. Os dois processos foram parados pelo usuário e permanecem parados para início humano. Credenciais preservadas. O controle de runtime pertence ao operador, conforme `AGENTS.md` e `../etl-dash/CONTEXTO_GLOBAL.md`.

Backup anterior: `target/publicacao-rodizio-20260911/satelite-anterior-6C5976AE.jar`. Manifesto de publicação, hashes e conferência SQL: `target/publicacao-rodizio-20260911/`. O atualizador ganhou o modo `database/subir_database.bat --rodizio`, usado para aplicar somente a V22; a segunda execução passou, confirmando idempotência. Oito colunas anuláveis conferidas e 23 timeouts preservados. O frontend continua preparado para publicação separada. A autorização externa de XML/consulta continua pendente conforme `fechamento-autorizacao-xml-timeouts-2026-09-11.md`.

## Diagnóstico confirmado

A conferência das capturas, API e SQL está em [conferencia-painel-xml-falhas-2026-09-11.md](conferencia-painel-xml-falhas-2026-09-11.md): 258 comparações aprovadas. Na leitura de 15:39–15:46, havia 231 comprovantes confirmados hoje e nenhum XML novo. O XML foi executado 22 vezes, mas repetiu a mesma página por causa de seis falhas antigas de obtenção HTTP 401. Os oito resultados de erro por ocorrência não representam oito downloads distintos.

Os quatro timeouts de comprovante observados hoje aconteceram após o download/preparo da imagem; o destino SOAP não respondeu dentro do prazo. Conexão SFTP OK e ciclo FALHA são compatíveis. Os 23 envios ambíguos acumulados continuam dependentes de conferência no destino.

## Alterações aplicadas

| Problema | Comportamento do candidato |
| --- | --- |
| XML e comprovantes monopolizavam a execução por etapas inteiras | Alternância por até dez avaliações ou 120 s entre chamadas. A chamada iniciada termina ou atinge seu timeout antes da troca. Não há espera PM2 de 30 minutos entre turnos ativos. |
| Página XML presa em erros já persistidos | Resultado interno `RETIDO` permite avanço somente com auditoria identificada e chaves válidas. Erro sem retenção preserva o cursor. Interrupção não salva uma página parcialmente processada. |
| Corrigir o token não recuperava os seis erros antigos | Recuperação específica por CT-e, limitada a dez registros e cooldown de 30 minutos, com releitura sob lock. Inclui falha comprovada de obtenção e envio impedido antes de iniciar; não inclui timeout SOAP ou recusa do destino. |
| Origem retornando 401/403 em sequência | Primeiro erro suspende novos downloads ESL no processo durante o cooldown. SFTP permanece preferencial. Não se escolhe outra credencial automaticamente. |
| XML ausente ficava sem retorno posterior | Resultado de obtenção ausente recebe motivo e tentativa datada, para recuperação futura sem bloquear novas páginas. Registros SFTP sem evidência de tentativa XML não entram nessa recuperação por suposição. |
| Resultado XML dependia do status do comprovante | Aceite XML passa a contar como sucesso da etapa, preservando o status, mensagem e restrição do comprovante. Pendências de origem têm contador próprio. |
| Inventário consumia a passagem inteira | Materialização em partes de até 100 entradas; consultas continuam limitadas a 500 chaves. O N+1 foi limitado por turno, mas ainda não eliminado. Escritas repetidas de rejeição idêntica e bloqueio de origem inalterado são evitadas. |
| Cancelar a espera podia deixar SOAP ainda executando | Semáforo permanece retido até a chamada real terminar, mesmo após cancelamento. A próxima chamada impedida antes de iniciar continua recuperável; o envio anterior sem resposta permanece ambíguo. Proteção por JVM. |
| Sucesso sem prova e baixa em CT-e de outra nota/mesma NF-e | Sucesso sem data, inclusive arquivado, impede reenvio e continua sem confirmação datada; seleção do log usa par ativo NF-e/CT-e; confirmação do comprovante usa o CT-e efetivo. Não se propaga baixa a todos os CT-es de uma NF-e. |
| Consulta histórica podia retornar várias linhas em `Optional` | Busca do legado limitada explicitamente à primeira confirmação datada. |
| Estado podia mudar entre selecionar e enviar | Releitura do registro ativo dentro do lock, com nova checagem de confirmação/restrição. |
| Histórico dizia CONCLUÍDO apesar de falha XML | Ciclo considera ambas as etapas; cada cliente conta uma vez no resumo de falhas. V22 grava métricas XML separadas e motivo sanitizado. |
| Falha intermediária perdia contagens já apuradas | Inventário e resultados acumulados são preservados, inclusive falha no meio do turno. Causa SQL/HTTP é resumida sem payload, caminho, documento ou credencial. |
| Perfil SFTP de outro cliente usaria serviço Vedacit | Perfil sem adaptador próprio é bloqueado antes da conexão. PPG/SELIA/SUPPORTE não são encaminhados à API da Vedacit. |
| Data de telemetria/cursor sofria conversão adicional | Novos carimbos usam America/Sao_Paulo e binding JDBC direto de `LocalDateTime`. Histórico e datas de etapas/ciclos preservados; round-trip real ainda depende do aceite operacional. |

O Dashboard apresenta XML e comprovantes separadamente e traduz a causa em linguagem de operação. Ciclos antigos mostram “sem medição” para XML e “motivo não registrado” quando necessário; nenhum zero histórico foi inventado. O proxy existente encaminha os novos campos sem consultar as integrações externas.

## Configuração

Valores preparados em `ecosystem.config.js`, sem alteração do processo PM2 existente:

| Variável | Padrão |
| --- | ---: |
| `WORK_SFTP_CLIENTES_TURN_ITEMS` | 10 avaliações |
| `WORK_SFTP_CLIENTES_TURN_MS` | 120000 ms |
| `WORK_SFTP_CLIENTES_INVENTORY_ITEMS_PER_TURN` | 100 entradas |
| `VEDACIT_XML_SOURCE_RETRY_ITEMS` | 10 registros |
| `VEDACIT_XML_SOURCE_RETRY_COOLDOWN_MS` | 1800000 ms |

O limite de envio do turno também respeita o menor teto do perfil e de `WORK_SFTP_CLIENTES_MAX_ITEMS`. O inventário é uma fotografia do ciclo: arquivos que chegam depois da listagem serão descobertos no ciclo seguinte. A posição interna da página XML permanece em memória durante os turnos; após reinício, retoma-se da última página inteira confirmada, reaplicando a idempotência dos documentos já concluídos.

## Validação e pacotes

- Backend: 361 testes executados, 358 aprovados, três manuais ignorados e nenhuma falha. JVM Java 17 com rede/banco externo bloqueados. Evidência: `target/unit-tests/coverage-20260911-164929-478/summary.json`.
- Cenários incluem mais de dez páginas XML, mil documentos em turnos de dez, ausência no primeiro item, inventário somente rejeitado, interrupção da página, cursor com retenção/sem retenção, recuperação de 401, preservação de timeout POD, SOAP que ignora interrupção, falha parcial de banco e bloqueio por migration ausente.
- Frontend: 23 testes direcionados aprovados, TypeScript e lint aprovados, build Vite concluído. Seis cenários de navegador, 390/1265/1920 px nos temas claro/escuro, sem transbordamento da página ou exceção JavaScript. Dados visuais artificiais, sem acesso à produção. Evidências: `../etl-dash/dashboards/frontend/.tmp/rodizio-visual/` e arquivos `rodizio-*.log` em `.tmp`.
- Candidato Java: `target/rodizio-xml-20260911-final/satelite-0.0.1-SNAPSHOT.jar`; SHA-256 `5FDE17354448448500800FF705EA5106EC9B69FF62985D14A67D7646F3D6E04D`. Empacotamento offline a partir das classes testadas e fontes SOAP já geradas, sem regenerar/editar contratos. Os 1.253 arquivos de classes/recursos foram comparados byte a byte com a saída validada; evidência em `target/rodizio-xml-20260911-final/verificacao.json`.
- Candidato frontend: `../etl-dash/dashboards/frontend/.tmp/quality-build/20260911-rodizio-xml-final/dist`.
- Migration: `database/sql/migration/V22__auditoria_etapas_ciclo_rodizio.sql`, aditiva e idempotente, com baseline V16 alinhado e campos históricos anuláveis. Aplicada às 17:09 pelo atualizador; repetição sem erro e leitura real SQL confirmaram a estrutura.
- JAR anterior preservado em backup: SHA-256 `6C5976AE48C418D4D8C5E2408E035DA49218B9F604C9D31A3B67234338DCADF6`. O caminho operacional agora contém o mesmo SHA-256 do candidato validado, `5FDE17354448448500800FF705EA5106EC9B69FF62985D14A67D7646F3D6E04D`.

## Publicação e pendências

JAR e banco estão prontos para os dois processos no cadastro PM2 existente; a preparação foi feita com ambos parados. O worker verifica a presença da estrutura V22 antes de conectar ou enviar. Resta o início humano e o aceite operacional, além da publicação separada do frontend. Nenhum comando de start/restart foi executado ou incluído no pacote.

Depois da publicação, conferir o avanço do cursor, a alternância entre as etapas e o contrato dos ciclos. Um XML correlacionado disponível no SFTP pode recuperar o documento sem consultar a ESL; quando o fallback for necessário, ele depende de autorização válida na ESL. O código não resolve um token sem permissão. Conciliar com a Vedacit os envios ambíguos, sem reenvio automático. Conferir novos horários contra SQL/logs antes de qualquer correção histórica.

PPG, SELIA e SUPPORTE continuam em seus fluxos e flags existentes. A execução paralela entre clientes e o uso de suas pastas SFTP exigem adaptadores próprios e controle agregado ESL entre processos. Não foram habilitados clientes, fallback global, recuperação das planilhas ou processos adicionais. Essas tarefas, a redução restante do N+1 e a conferência de sucessos sem data permanecem no `states.md`.
