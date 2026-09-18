# Data de entrega no comprovante Vedacit — investigação de 18/09/2026

**Atualização operacional, 18/09 às 17:37:54 BRT:** a correção foi instalada no JAR usado pelo PM2, SHA-256 `EFF36F937715338675CCA3F52A2C4429CC2B70AFA8EC4CEA6A7C9E46AB908A2F`. API, worker e supervisor noturno estão parados, aguardando início humano. Os registros abaixo preservam a investigação e os cortes anteriores; os detalhes da instalação estão no final. Ainda falta comprovar um novo envio produtivo e a data recebida pela Vedacit.

## Conclusão

O fluxo SFTP investigado preenchia `DataEntregaNota` com uma data técnica da auditoria, em vez de obter a data da ocorrência real de entrega. A regra foi confirmada no código e no bytecode do JAR então operacional `78B8B50695186C597C5672929303256D0F69E397D10243B29245050A1830DB49`. Há divergência concreta no exemplo enviado ao gestor: NF 244920, CT-e 57982, log 71230.

O aceite do comprovante comprova que a chamada foi aceita; não valida a data de entrega informada. Esta investigação não examinou a imagem nem consultou o valor atualmente exibido na tela da Vedacit.

## Exemplo com identidade exata

Todos os horários abaixo são de **18/09/2026, America/Sao_Paulo (UTC−03:00)**.

| Informação | Horário | Evidência |
|---|---|---|
| Entrega registrada na ESL (`occurrence_at`) | 11:08:30 | Ocorrência 313075894, código 1, `Entrega Realizada Normalmente` |
| Inclusão dessa ocorrência na ESL (`created_at`) | 11:09:27.135 | Mesma resposta; não confundir inclusão com entrega |
| Processamento do XML na auditoria | 12:51:39.949 | `data_processamento_dados`, log 71230 |
| Valor que o fluxo monta em `DataEntregaNota` | 12:51:39 | Derivação determinística do código empacotado e do registro SQL |
| Início da chamada de comprovante | 12:53:39.386 | Log do worker |
| Retorno de sucesso do comprovante | 12:55:18.757 | Log do worker |
| Aceite persistido | 12:55:18.762 | Log 71230 e confirmação permanente do par |

A diferença entre a entrega registrada e o valor montado no campo de entrega é **1 h 43 min 9 s**. A consulta ESL de 15:37:43 retornou HTTP 200, exatamente uma ocorrência, ambas as chaves NF-e/CT-e iguais às do comprovante e `next_id=null`. A data correta está disponível na origem para esse exemplo.

O corpo original da requisição e a resposta SOAP integral não foram armazenados (`request_payload` e `response_payload` nulos). Portanto, o valor do campo é reconstruído pelo código operacional, não extraído de uma captura do envelope nem da tela do destino. O envio e seu aceite possuem evidência direta nos logs e no banco.

## Causa técnica e alcance

1. `EtlRepescagemService.processarComLockCliente` chama `EtlRegistroService.reprocessarCanhotoVedacitPorCte` com a fonte SFTP.
2. `reconstruirOcorrenciaVedacit` escolhe `dataProcessamentoDados`; na ausência, `dataProcessamento`; na ausência de ambas, o relógio da auditoria. Constrói uma ocorrência interna de código 1 usando essa data em `occurrenceAt` e `createdAt`.
3. `VedacitIntegrationService` formata `occurrenceAt` como `dd/MM/yyyy HH:mm:ss` e o atribui a `DataEntregaNota`. `DataEnvioCanhoto` é preenchida separadamente com o horário corrente.
4. O SFTP fornece a imagem correlacionada. Seu contrato local contém chaves, tamanho e modificação do arquivo, sem um campo estruturado de data real de entrega. Não há extração da data escrita na imagem. Data de modificação/upload também não comprova entrega.

O erro está na reconstrução da ocorrência para esse caminho de comprovante. A conversão final usa corretamente a propriedade recebida, mas ela já contém a informação técnica errada. O caminho que recebe uma ocorrência real ESL tem origem diferente para essa propriedade. A revisão noturna também pode acionar a mesma rotina de comprovantes.

O campo não está vazio e não é sempre a data de envio: sua preferência é a data de processamento do XML, que pode ser anterior. A regra é inadequada para representar a entrega, embora em alguns documentos os valores possam coincidir. Uma amostra não quantifica todas as divergências históricas.

O JAR padrão não foi substituído: última alteração em 13/09 às 10:27:39. As duas classes foram extraídas e inspecionadas com `javap`, sem executar Spring. Os processos API/noturno observados usam esse caminho; o ciclo do exemplo está registrado no worker. A inspeção do pacote evita atribuir ao runtime uma mudança existente apenas no fonte.

## O que precisa mudar

- Obter a data da ocorrência ESL de entrega (`code == 1`, `occurrence_at`) e correlacionar exatamente NF-e e CT-e antes de preencher `DataEntregaNota`.
- Preservar separadamente a data de envio. Não substituir entrega por emissão/processamento de XML, inclusão do evento, upload do arquivo ou horário corrente.
- Definir o tratamento de ocorrência ausente, múltiplos eventos e indisponibilidade da origem; não fabricar data. A imagem pode continuar exclusivamente no SFTP, com consulta separada aos metadados da entrega conforme o escopo aprovado.
- Para documentos já aceitos, alinhar com a Vedacit uma forma de retificar a data, preservando aceites e proteção contra duplicidade. Não reenviar comprovantes automaticamente para corrigir um campo.
- Validar com a Vedacit se o campo recebido atualiza a informação esperada e se a planilha possui outras atualizações além da entrega. A correção desse campo, isoladamente, não comprova que a planilha inteira pode ser retirada.

## Evidências e limite da atuação

- SQL somente leitura, com rollback e exclusivamente em `SATELITE_TMS_AUDITORIA`: [20260918_data_entrega_comprovante_vedacit.sql](../database/sql/diagnostico/20260918_data_entrega_comprovante_vedacit.sql).
- Evidências locais em `target/data-entrega-vedacit-20260918/`: `auditoria.json`, `ocorrencia-esl.json`, `log-exemplo-sanitizado.txt`, `manifesto.json` e bytecode das duas classes operacionais.
- Fonte: `EtlRegistroService.java:955`, `VedacitIntegrationService.java:278` e `:510`; contrato documental em `VedacitSftpDocument`.
- Uma consulta REST de leitura à ESL. Nenhum envio SOAP, mudança de credencial, alteração de banco, controle de processo ou modificação de código Java. Registro documental do achado e da pendência; correção não implementada nesta investigação.

## Candidato local implementado e validado

Em 18/09/2026 foi construído um candidato local, sem instalar JAR, iniciar processos, alterar credenciais, consultar serviços externos adicionais ou transmitir SOAP.

| Para leigos | Antes | Depois no candidato |
|---|---|---|
| Data que vai no comprovante | Horário técnico de processamento do XML/auditoria. No exemplo: **12:51:39**. | Horário real da entrega na ESL. No exemplo: **11:08:30**. |
| Como a entrega é reconhecida | A rotina de reprocessamento criava um evento interno com uma data técnica. | Só aceita ocorrência ESL de código `1` cujo par NF-e/CT-e coincide exatamente com o comprovante. |
| Quando não há certeza | Podia usar uma data técnica substituta. | Mantém pendente recuperável; não chama SOAP e não cria timeout de envio. |
| Imagem do canhoto no lote SFTP exclusivo | SFTP. | Continua exclusivamente SFTP. A nova consulta ESL obtém somente o metadado de data da entrega. |

### Implementação

- `VedacitDataEntregaService` consulta `RodogarciaClient.buscarOcorrencias` com `RODOGARCIA_TOKEN_VEDACIT`, `invoice_key`, código `1`, paginação local e telemetria/política ESL já existentes. Não usa token master/XML, cursor produtivo ou fallback documental.
- A resolução distingue entrega encontrada, ausência, ambiguidade, data inválida, resposta/paginação incompleta e indisponibilidade. Cursor repetido ou limite de páginas não é interpretado como ausência.
- `EtlRegistroService` resolve a data após definir o CT-e efetivo e antes de `registrarInicioEnvioCanhoto`; relê o registro sob lock imediatamente antes do efeito remoto. Aceite permanente, timeout/bloqueio e arquivamento continuam impedindo envio.
- `VedacitIntegrationService` formata a data recebida em `America/Sao_Paulo`, `dd/MM/yyyy HH:mm:ss`, inclusive quando a ESL devolver UTC. `DataEnvioCanhoto` permanece o horário independente de envio.
- Não houve migration: a pendência usa `mensagem_erro_canhoto` sanitizada e classificação recuperável já existentes, sem persistir payload fiscal, imagem ou novo estado de domínio.

### Teste e pacote

O teste dirigido final passou com **128/128**, zero falhas/erros/ignorados, Java 17 e bloqueio de rede ativo na JVM. Ele capturou o `Canhoto` enviado ao proxy SOAP mockado: para a regressão, `DataEntregaNota` foi `18/09/2026 11:08:30`, não `18/09/2026 12:51:39`; também cobriu UTC/BRT, página posterior, cursor repetido, ausência, datas conflitantes, 401/403/429/5xx, timeout, credencial ausente, aceites, timeouts, fila, reconciliação e releitura/arquivamento concorrente.

O candidato é `target/candidatos/data-entrega-vedacit-20260918-final/satelite-0.0.1-SNAPSHOT.jar`, SHA-256 `D960376364872035860DFBE623D143D2F588835CCABAC9F6B6BA355AD85B44EB`; contém as classes alteradas e os três WSDLs Vedacit. O JAR operacional continua `78B8B50695186C597C5672929303256D0F69E397D10243B29245050A1830DB49`, sem alteração.

### Publicação e aceite ainda necessários

Antes de qualquer instalação, o responsável operacional deve confirmar D1 (retenção sem data) e D2 (eventos de entrega com datas conflitantes), autorizar a troca do artefato e guardar o JAR operacional como backup. A instalação humana deve conferir o hash do candidato, não reutilizar o log 71230 nem outro aceite prévio e observar somente um novo comprovante elegível de par exato. O aceite real exige comparar o `occurrence_at` ESL, a data recebida/exibida pela Vedacit e a auditoria local, além de confirmar que não houve duplicidade. Se faltar confirmação do destino, parar no resultado revisável; não reenviar aceites nem retirar a planilha.

Retificação histórica e eventual substituição da planilha continuam dependências exclusivas da Vedacit.

## Conferência após reinício humano — 18/09, 17:23–17:26 BRT

**A API e o worker reiniciaram com sucesso, mas continuam na versão antiga. A correção não foi instalada no caminho operacional.** O cadastro efetivo PM2, a identidade das JVMs, os logs de inicialização e o hash do arquivo concordam:

| Processo | PID | Início | Situação no corte |
|---|---|---|---|
| Satelite-API-19090 | 52260 | 18/09 às 17:22:41 | Online, HTTP 200, JAR antigo |
| WORK-SFTP-CLIENTES | 66888 | 18/09 às 17:22:37 | Online, ciclo aberto, JAR antigo |
| VEDACIT-RECONCILIACAO-NOTURNA | 51416 | 13/09 às 10:35:05 | Online, sem recarga da correção |

Os três cadastros apontam para `target/satelite-0.0.1-SNAPSHOT.jar`. Esse arquivo conserva **79.440.073 bytes**, última gravação em **13/09 às 10:27:39**, SHA-256 **78B8B50695186C597C5672929303256D0F69E397D10243B29245050A1830DB49**, e não contém `VedacitDataEntregaService.class`.

O candidato isolado continua em `target/candidatos/data-entrega-vedacit-20260918-final/satelite-0.0.1-SNAPSHOT.jar`, SHA-256 **D960376364872035860DFBE623D143D2F588835CCABAC9F6B6BA355AD85B44EB**, com a classe nova. Nesta conferência, **1.269 classes e arquivos WSDL/XSD** do candidato foram comparados por SHA-256 com a saída da suíte `coverage-20260918-165421-552`: zero diferenças e zero ausências. O relatório da suíte confirma **128/128 aprovados**, incluindo captura do `Canhoto` no proxy SOAP simulado. Não foi necessário repetir a suíte nem provocar envio real para comprovar essa equivalência.

A API local registrou ciclo iniciado às 17:23:05.580 e parcial atualizado às 17:25:51.547, com uma avaliação XML/uma retenção e zero comprovantes enviados até esse corte. O ciclo ainda estava aberto; zero em parcial não significa fila vazia. A consulta às 17:25:56 respondeu HTTP 200. Os PIDs e contadores PM2 permaneceram estáveis entre as duas leituras; os contadores acumulados não foram tratados como falhas novas.

### Prova JSON antes/depois e seu alcance

- `target/aceite-data-entrega-20260918/antes.json`: exemplo produtivo NF 244920/CT-e 57982/log 71230, entrega ESL 11:08:30 versus campo montado 12:51:39, reconstruído pelo código/auditoria. Não é captura original SOAP.
- `target/aceite-data-entrega-20260918/depois-teste-local.json`: captura do campo 11:08:30 no teste com identidades sintéticas e horários da regressão, sem envio ou retificação da nota real.
- `target/aceite-data-entrega-20260918/prova-antes-depois.json`: consolida os dois níveis de prova, hashes, processos e o resultado `PROCESSOS_REINICIADOS_COM_JAR_ANTIGO_CORRECAO_NAO_INSTALADA`.
- Evidências auxiliares na mesma pasta: `pm2-sanitizado.json`, `api-ciclos.json`, `comparacao-candidato-testado.json` e logs da inicialização.

**Próxima etapa:** instalar efetivamente o candidato validado no caminho executado pelo PM2 e recarregar os processos abrangidos pelo procedimento autorizado, incluindo o supervisor noturno. Somente depois será possível obter o “depois” produtivo com um novo comprovante elegível e conferir o recebimento/uso da data no destino. Não reenviar um aceite existente para produzir essa prova.

Esta conferência apenas leu artefatos, processos, logs e a API local. Não modificou Java, JAR, configuração, credencial, banco ou processos; não houve nova consulta ESL nem chamada SOAP de diagnóstico. As provas anteriores foram reutilizadas com sua data e limites originais.

## Instalação autorizada e limpeza dos avisos — 18/09, 17:37:54 BRT

Após a conferência anterior, o usuário informou ter parado API/worker e pediu preparar o pacote para sua próxima partida pelo PM2. Também solicitou a correção de onze avisos Java: dez imports não utilizados foram removidos em oito arquivos; o mock que retornava dois `Optional<LogIntegracaoModel>` por varargs passou a encadear dois `thenReturn`, preservando a sequência e eliminando a criação do array genérico. Nenhuma regra adicional de negócio foi alterada nesta limpeza.

A suíte isolada Java 17 passou com **152 testes, todos aprovados, zero falhas/erros/ignorados**, com rede bloqueada. A seleção anterior foi ampliada somente para incluir os testes de scheduler, reconciliação e consulta também tocados pelos avisos. Compilação e testes estão em `target/unit-tests/coverage-20260918-173353-954/`; o `Canhoto` mockado continua levando `18/09/2026 11:08:30` no caso de regressão. O empacotamento reutilizou exatamente as classes testadas e recursos locais, sem executar a aplicação ou baixar contratos.

| Evidência | Resultado |
|---|---|
| JAR instalado | `target/satelite-0.0.1-SNAPSHOT.jar`, 79.447.684 bytes |
| SHA-256 instalado/candidato | `EFF36F937715338675CCA3F52A2C4429CC2B70AFA8EC4CEA6A7C9E46AB908A2F` |
| Candidato preservado | `target/candidatos/data-entrega-vedacit-20260918-avisos/satelite-0.0.1-SNAPSHOT.jar` |
| Comparação bidirecional com os testados | 1.272 arquivos, incluindo 1.269 classes/contratos; zero ausências/divergências |
| Backup anterior | `target/backup-pacotes/satelite-antes-data-entrega-20260918-173750.jar` |
| SHA-256 backup | `78B8B50695186C597C5672929303256D0F69E397D10243B29245050A1830DB49` |
| Processos após a instalação | API ID 7, worker ID 189 e noturno ID 191: `stopped`, PID 0; nenhuma JVM residual do JAR |

Os três cadastros PM2 apontam para o mesmo JAR. O noturno ainda mantinha a versão anterior aberta; foi parado para permitir a substituição. A primeira parada deixou `waiting restart`; uma segunda parada cancelou a espera. Não houve reinício ou nova JVM, e os cadastros/ambientes foram preservados. O usuário deverá retomar também `VEDACIT-RECONCILIACAO-NOTURNA`, além dos dois processos que já havia parado. Nenhum processo foi iniciado pela automação; não houve consulta ESL/SOAP, envio ou escrita no banco nesta instalação.

A troca foi autorizada explicitamente pelo usuário, superando a pendência de autorização registrada no corte anterior. O comportamento instalado mantém pendentes os documentos sem data segura e retém datas conflitantes. Alinhamento com o gestor, escolha de evento em conflito, retificação histórica e substituição da planilha conservam seus limites próprios; não exigem repetir a aprovação da instalação concluída.

Evidências finais em `target/publicacao-data-entrega-20260918/`: `instalacao.json`, `comparacao-conteudo.json`, `build.log` e `prova-antes-depois.json`. Este último preserva o antes produtivo reconstruído e o depois do teste local, agora associado ao hash efetivamente instalado. **Não é prova de envio produtivo com a correção:** os processos continuam parados e o valor no destino ainda não foi conferido. Após o início humano, verificar somente um novo par elegível; não reenviar o log 71230 ou qualquer aceite anterior para obter a prova.
