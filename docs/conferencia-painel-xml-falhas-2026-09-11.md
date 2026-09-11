# Conferência do painel, XML e falhas — 11/09/2026

Fotografia conferida entre **15:39 e 15:46 BRT**, com o último ciclo finalizado às **15:19:31**. A captura do usuário coincide com os endpoints locais e com as consultas em `SATELITE_TMS_AUDITORIA`. A matemática dos números confere, mas eles representam escopos diferentes. A execução XML está bloqueada e o histórico exibido cobre somente a etapa de comprovantes.

## XML executou hoje?

**A rotina XML executou 22 vezes hoje; nenhum XML novo foi confirmado.** Entre 04:19 e 15:15, todos os resumos repetiram: uma página, 20 ocorrências, 12 já processadas, oito resultados de erro e cursor preservado em `305833355`. A origem não foi esgotada.

O SQL independente, deduplicado pela primeira confirmação do documento, confirmou:

| Etapa | 10/09 | 11/09 até a conferência | Última confirmação |
|---|---:|---:|---|
| XML/CT-e | 304 | 0 | 10/09 às 23:27:29 |
| Comprovante | 50 | 231 | 11/09 às 03:48:37 |

Os 304 XMLs de ontem foram aceitos entre 23:15:14 e 23:27:29. Em setembro, a API mostra 304 XMLs e 544 comprovantes. Desde 03:48 não há novo aceite de comprovante na fotografia analisada.

O ciclo que começou a processar comprovantes em **10/09 às 23:27:52** terminou em **11/09 às 03:48:44**: 280 selecionados = **277 enviados + dois timeouts + um pendente**. Desses 277 envios, 46 ocorreram antes da meia-noite e 231 depois. O histórico inclui os 277 no dia 11 porque filtra pela **data de término do ciclo**; o gráfico de envios considera a data individual de cada etapa. Não há divergência entre essas contagens.

### Causa do bloqueio XML

1. Na última página de ontem, seis downloads XML pela ESL falharam com **HTTP 401 Unauthorized**. Os registros técnicos `10561` a `10566` continuam em `ERRO_DESTINO`, todos com uma tentativa e datas de 10/09, 23:27:33–23:27:52. O acesso de download usa `RODOGARCIA_MASTER_API_REST`; esta investigação não expôs nem substituiu a credencial, nem fez nova chamada ESL.
2. `EtlRegistroService.processarEmissaoXmlVedacitComExclusao` devolve `ERRO` imediatamente quando encontra `status_dados=ERRO_DESTINO`. A proteção trata esses erros de obtenção documental como os resultados de envio que precisam ficar retidos.
3. `EtlFluxoDestinoService` encerra a paginação incremental e não avança o cursor quando a página contém qualquer erro. Assim o ciclo seguinte volta à mesma página, encontra o erro persistido e termina novamente. **Renovar a credencial, sozinho, não garante retomada**, pois esse ramo retorna antes de tentar baixar o documento outra vez.

Hoje o log consolidado não registra novo erro de download XML: registra 22 reapresentações do estado de erro. As oito falhas do resumo são resultados por ocorrência; o banco contém seis registros XML com 401. Não se deve chamar isso de oito downloads ou oito CT-es distintos. A correspondência exata das 20 ocorrências não foi reconstruída por nova consulta externa.

As flags XML e dreno estão habilitadas no cadastro persistido do worker; os logs comprovam sua execução. O JAR operacional permanece `6C5976AE48C418D4D8C5E2408E035DA49218B9F604C9D31A3B67234338DCADF6`. Não se trata de XML desligado ou de atingir um limite diário.

## Os saldos da captura conferem?

Todos os 18 valores das seis linhas coincidiram com a API e com a decomposição SQL dos registros ativos:

| Integração / etapa | Pendentes | Bloqueados | A conferir |
|---|---:|---:|---:|
| Vedacit — XML/Dados | 6 | 554 | 1.333 |
| Vedacit — Comprovante | 3 | 614 | 114 |
| SELIA — AddEvents | 2 | 0 | 0 |
| SELIA — POD/Comprovante | 1 | 0 | 1 |
| PPG — XML/Dados | 0 | 0 | 39 |
| PPG — Comprovante | 0 | 0 | 39 |

Detalhamento que explica os números:

- **Seis pendentes XML:** são os seis erros 401 persistidos. A classificação analítica `PENDENTE` não comprova que o registro esteja elegível ao processamento automático atual.
- **554 bloqueados XML:** 552 documentos representados por pendências de comprovante com dados ainda pendentes, mais dois registros técnicos de arquivos instáveis com `PENDENTE_ORIGEM`.
- **Três pendentes de comprovante:** um `PENDENTE_ENVIO` e dois `PENDENTE_TECNICO` de upload instável. A fila normal do worker tem somente **um**, cujo comprovante do CT-e exato continua indisponível; os logs confirmam que a ESL não foi consultada para esse canhoto.
- **614 bloqueados de comprovante:** 552 pares com dependência de XML + 38 registros de nomes sem NF-e/CT-e válidos + uma recusa do destino + 23 timeouts ambíguos.
- **1.333 XMLs a conferir:** status `SUCESSO` sem data da etapa XML. Isso não comprova envio hoje e não autoriza reenviar nem alterar sucesso sem conciliação.
- **114 comprovantes a conferir:** 108 sucessos sem data de etapa + seis registros `RECEBIDO` sem classificação conclusiva.
- **39 PPG em cada etapa:** 39 identidades de ocorrência deduplicadas com status da etapa ausente; não são 39 envios novos nem 78 documentos distintos. SELIA tem uma entrega pendente de foto e um erro em dados; o POD adicional a conferir está como `RECEBIDO`.

### Por que 614 no painel e 594 no ciclo?

O ciclo registra **594 linhas bloqueadas** do cliente SFTP: 555 `BLOQUEADO_ORIGEM` com dados pendentes + 38 nomes inválidos + uma recusa. Os **23 timeouts** são apresentados separadamente. O painel por etapa inclui timeouts em bloqueados e aplica deduplicação/prioridade da confirmação datada: **594 + 23 − três cópias superadas = 614**.

As três cópias também explicam a passagem de 557 linhas com dados pendentes para 554 documentos no indicador XML. Elas permanecem fisicamente na auditoria. Nenhuma limpeza ou reclassificação foi feita.

Os **3.164 arquivos reconhecidos** são o inventário de comprovantes daquela listagem, que inclui arquivos já enviados. Não representam 3.164 documentos prontos nem quantidade de XMLs. Os **40 rejeitados** naquele ciclo correspondem aos 38 nomes inválidos e dois uploads ainda instáveis; a rejeição de inventário não equivale a falha de conexão ou recusa SOAP.

## Razão das falhas de comprovantes

| Ciclo finalizado | SFTP | Preparação e chamada SOAP | Resultado |
|---|---|---|---|
| 14:45:06 | Conectou e baixou o arquivo | Imagem preparada; envio às 14:41:55; erro às 14:44:58 | `SocketTimeoutException: Read timed out` |
| 15:19:31 | Conectou e baixou o arquivo | Imagem preparada; envio às 15:16:17; erro às 15:19:20 | `SocketTimeoutException: Read timed out` |

Em cada ciclo foram avaliadas duas NF-es: uma permaneceu sem comprovante correspondente e a outra ficou aproximadamente **183 segundos** aguardando a resposta SOAP. Não houve aceite recebido. Os registros `10583` e `10608` ficaram em `TIMEOUT_AMBIGUO`, e o total retido passou de 21 para 22 e depois 23.

Outros dois timeouts ocorreram às 03:03:14 e 03:06:20, nos registros `10009` e `10056`. Assim se confirmam **quatro falhas de comprovante hoje**. O SQL e o log concordam.

O timeout prova ausência de resposta dentro do prazo, mas não determina se a Vedacit recebeu o documento nem distingue lentidão do serviço de uma falha de rede após o envio. É necessário conciliar no destino antes de qualquer reenvio desses quatro casos. Não houve erro de autenticação SFTP, conversão de imagem ou estouro de parâmetros SQL nesses dois ciclos.

## Limitações reais de apresentação

- O histórico guarda o resultado da **etapa SFTP de comprovantes**, criada depois da rotina XML. Por isso há **20 ciclos `CONCLUIDO` com oito erros XML na mesma execução**. O worker registra falha no resultado global, mas esse resultado não é persistido na linha exibida. A duração de 229 s também exclui a etapa XML e a inicialização do Java. Esse histórico não serve para avaliar sozinho a saúde integral XML + comprovante.
- `clientes_falhos=2` no resumo do worker significa, nesses casos, falha XML e falha de comprovante no mesmo cliente. Não são dois clientes distintos.
- O contrato do ciclo não possui etapa/código/motivo da falha. A razão acima precisou ser reconstruída dos logs e da auditoria por documento.
- `falhasPeriodo=0` para XML em 11/09 usa a data da etapa, ainda de ontem nos seis registros. Não significa que a rotina XML de hoje esteja saudável. O indicador descreve o estado datado da auditoria, não cada execução/tentativa reapresentada.
- Foi observada diferença de horários em metadados auxiliares: os seis 401 das 23:27 BRT constam em `tb_esl_request_telemetria.data_evento` às 05:27 do dia seguinte, deslocamento de seis horas; a última chamada de ocorrência das 15:15 consta às 21:15. O carimbo do cursor também apresenta deslocamento de três horas em relação ao log do avanço. Os horários individuais de XML/comprovante e os ciclos conferidos coincidem com BRT. Não usar a data bruta da telemetria ESL para afirmar que aqueles seis downloads ocorreram hoje. A causa completa da conversão de fuso exige validação separada do caminho JPA/JDBC; não foi aplicada correção retroativa.

## Próximos ajustes identificados

1. Separar erro comprovado de **obtenção XML na origem** de recusa/resultado ambíguo de envio. Preparar recuperação idempotente dos seis 401 após validar a credencial/fonte, sem liberar timeout ou sucesso para reenvio.
2. Evitar que registros já retidos paralisem indefinidamente toda a paginação XML. A solução precisa preservar uma fila durável de recuperação e retomada auditável; não saltar/resetar o cursor manualmente.
3. Expor execução/resultado XML separadamente do comprovante, com motivo sanitizado e distinção entre pendência analítica e fila elegível. Preservar a alternância XML/comprovantes já registrada como pendência.
4. Conciliar os quatro timeouts do dia com a Vedacit, revisar os 38 nomes inválidos com a origem e manter a correlação exata do comprovante pendente.
5. Corrigir a convenção de fuso da telemetria/cursor após reprodução isolada; preservar a auditoria histórica.

## Evidência e validação

- SQL versionado: `database/sql/diagnostico/20260911_conferencia_xml_comprovantes.sql`. Onze consultas somente leitura, conexão validada exclusivamente na base permitida, timeout de consulta/lock e rollback; nenhum DDL/DML.
- Evidências locais ignoradas pelo Git: `target/verificacao-painel-20260911/`: JSONs da API, `sql-conferencia.txt`, logs sanitizados, sonda Java e verificador `conferir.js`.
- Verificador: **258 comparações aprovadas**, cobrindo os 18 valores da captura contra API/SQL, envios do dia, paridade da série mensal e os nove contadores de cada um dos 23 ciclos.
- Apenas diagnóstico e documentação. Código da aplicação, JAR, configurações, cursores e processos foram preservados. Não houve consulta externa adicional, envio de integração ou mudança no banco.
