# Rastreio Vedacit — 12/09/2026, após parada da API e do worker

O erro do ciclo das 20:06 não indica queda do robô. O SFTP conectou e foi lido; o download alternativo de XML foi recusado pela ESL. A investigação posterior encontrou e corrigiu também um defeito no acesso alternativo aos comprovantes. A API e o worker foram parados pelo usuário e permanecem sob seu controle.

## O que foi comprovado

| Verificação | Resultado | Consequência |
| --- | --- | --- |
| XML `/api/ctes`, credencial REST configurada | HTTP 401, tanto com Bearer quanto com o formato Token indicado pela resposta | Sem XML no SFTP, essa credencial não permite recuperar o documento. Não é timeout nem falha de conexão. |
| Mesmo XML com credencial de cliente e com a credencial GraphQL já existente no ETL | HTTP 401 | As credenciais disponíveis dessas operações também não resolvem o acesso REST ao XML. Nenhuma foi substituída no `.env`. |
| Ocorrência Vedacit com token de cliente | HTTP 200, ocorrência encontrada | A leitura de eventos continua autorizada; isso não comprova permissão de download XML. |
| Comprovante na rota geral usada anteriormente | HTTP 401 | O serviço escolhia implicitamente o token master do XML, em vez do contrato de cliente. |
| Comprovante na rota `/api/customer/freight_delivery_receipts`, token Vedacit, log 8331 | HTTP 200, uma imagem, CT-e exato e DTO compatível | Contrato de cliente validado com controle positivo. |
| Mesma rota correta, log 7579 / NF 233409 / CT-e 53102 | HTTP 200, lista vazia | O comprovante pendente continua ausente para esse CT-e. O arquivo do CT-e 52321 da mesma NF já tem aceite e não foi reutilizado. |
| Todos os 38 arquivos rejeitados | 38 consultas GraphQL HTTP 200; uma correspondência exata de NF e número de frete por arquivo; zero CT-e vinculado; nenhuma página restante | O problema não é somente o nome do arquivo: falta o CT-e na origem. Não renomear nem preencher a chave por aproximação. |
| CT-e da amostra XML retida, log 8818 | GraphQL encontrou CT-e exato, ativo e autorizado, com link de PDF | O CT-e existe na origem. O contrato consultado não oferece o XML bruto; PDF e campos fiscais não substituem o XML oficial. |
| Estrutura SFTP Vedacit às 20:40 | Somente `xml` e `comprovantes`; 1.503 XMLs, 3.284 comprovantes; nenhuma subpasta adicional | Não existe outra pasta de documentos dentro da área Vedacit que o leitor esteja ignorando. A conferência anterior dos 79 pares retidos não encontrou seu XML nessa pasta. |
| Consultas SOAP reais de CT-e e canhoto às 20:44–20:46, controle 8331 | Ambas responderam com SOAP Fault: método sem permissão | O impedimento de conferência no destino persiste; não significa que o documento esteja ausente. |

Os 3.284 comprovantes são os **3.246 reconhecidos + 38 rejeitados** do painel. A lista individual dos rejeitados está em [rastreio-vedacit-rejeitados-20260912.csv](rastreio-vedacit-rejeitados-20260912.csv), sem chaves fiscais ou credenciais.

O teste de duas alternativas de rota de XML para cliente retornou 404. A introspecção GraphQL disponível confirmou tipos de CT-e, frete e documentos, mas não forneceu XML bruto nesses contratos. A credencial REST existente no ETL é a mesma configurada no Satélite; portanto, não há uma credencial REST diferente nesse arquivo para substituir a atual. HTTP 401 prova recusa de acesso, sem distinguir sozinho revogação, expiração ou escopo.

## Correção aplicada no código

- Acrescentado o contrato de comprovante de cliente no OpenFeign. Seu caminho é configurável por `RODOGARCIA_CUSTOMER_DELIVERY_RECEIPTS_PATH`, com padrão `/api/customer/freight_delivery_receipts`.
- Vedacit sem `RODOGARCIA_TOKEN_VEDACIT_COMPROVANTE` explícito usa a rota de cliente e `RODOGARCIA_TOKEN_VEDACIT`. O token master do XML deixou de ser escolhido implicitamente para comprovantes.
- Credencial documental explicitamente configurada mantém a rota geral anterior. Os demais destinos conservam seus contratos e seleção de credencial.
- O comprovante REST recebido ou fornecido ao serviço Vedacit precisa conter `freight.cte_key` igual ao CT-e solicitado e imagem presente. Resposta vazia, identidade ausente ou CT-e divergente permanecem pendentes, sem download nem envio. Em uma lista mista, somente o item do CT-e exato pode ser usado.
- A reconciliação reconhece o texto real da Vedacit, `Método ... sem permissão`, como `CONSULTA_SEM_PERMISSAO`. Antes, ele caía em `CONSULTA_INDISPONIVEL`. O bloqueio da consulta permanece, mas o relatório informa a causa correta. O novo teste reproduz os SOAP Faults de ambos os métodos sem guardar a mensagem externa.

O worker e a revisão noturna continuam no modo **SFTP exclusivo para comprovantes**. Essa correção de acesso alternativo não habilita fallback global nem desbloqueia o XML. Nenhum documento foi enviado durante o rastreio; as chamadas GraphQL foram somente consultas, e a SQL de amostras foi executada com rollback exclusivamente em `SATELITE_TMS_AUDITORIA`.

## O que ainda depende da origem ou do destino

1. **ESL/Rodogarcia:** autorizar o download de XML em `GET /api/ctes?key=...` para a credencial REST configurada, ou disponibilizar os XMLs oficiais ausentes na pasta Vedacit. Caso a rota contratual tenha mudado, fornecer o contrato verificável. Não alterar a URL ou credencial por suposição.
2. **Origem documental:** disponibilizar o comprovante do CT-e 53102/NF 233409 e corrigir a ausência de CT-e nos 38 fretes identificados no CSV.
3. **Vedacit/MultiTMS:** liberar os métodos `BuscarCTePorChave` e `BuscarCanhotoPorChaveNFe` para a credencial da integração. As duas recusas foram reconfirmadas às 20:46, usando os proxies e cabeçalhos do serviço real. A conferência dos 465 XMLs sem data e dos 25 timeouts depende de consulta autorizada e identidade exata. A rotina noturna registra a recusa e revisa novamente na próxima madrugada; ela não inventa aceite/data e não repete um envio incerto.

Aguardar sozinho não corrige uma permissão recusada. Com os acessos atuais, o robô consegue revisar, registrar a causa e recuperar documentos que aparecerem no SFTP; não consegue obter um XML que não está disponível por nenhuma das fontes verificadas.

## Validação e pacote

Os 64 testes direcionados passaram sem falhas ou erros, incluindo contrato Feign, DTO, escolha de rota/token, CT-e divergente, resposta vazia, SFTP exclusivo e regressão do orquestrador. Evidência: `target/unit-tests/coverage-20260912-203838-868/summary.json`.

Após incluir a correção do diagnóstico SOAP, a bateria completa foi repetida: **433 testes, 430 aprovados, zero falhas/erros e três manuais desabilitados**, com Java 17 e rede bloqueada na JVM de testes. Cobertura de linhas do código próprio: **71,92%**. Evidência final: `target/unit-tests/coverage-20260912-204737-710/summary.json`.

Pacote executável instalado no caminho normal `target/satelite-0.0.1-SNAPSHOT.jar` às **20:51:27**, SHA-256 `A43925272F206711C802084875DEBED0AF5AC5722D0C0E7B9392D82266226303`. As **1.269 entradas de aplicação** são idênticas às classes/recursos testados. Sem `.env`, classes de teste ou H2 no pacote. Backup anterior preservado em `target/backup-pacotes/satelite-antes-rastreio-20260912-205126.jar` (05A4BC9C...). Manifesto em `target/rastreio-acesso-20260912/pacote-instalado.json`.

API e worker foram conferidos `stopped`/PID zero antes da instalação e não foram acionados. O supervisor noturno permanece online com a cópia anterior 05A4BC9C...; na retomada humana, carregar os três processos pelo **ecosystem principal existente** para que a correção de diagnóstico também entre no supervisor. Nenhum ecosystem ou instalador adicional foi criado, e não há migration a aplicar. O JAR novo ainda não foi iniciado em produção; teste aprovado não equivale à liberação dos acessos externos.

Evidências de acesso em `target/rastreio-acesso-20260912/`; probe reproduzível em `scripts/probes/DiagnosticoAcessoEslProbe.java` e SQL em `database/sql/diagnostico/20260912_rastrear_acesso_xml.sql`. Os relatórios não incluem tokens, XML, imagens ou URLs assinadas.

## Acompanhamento da subida humana às 20:55

API PID 22560 e worker PID 19980 iniciaram o pacote A4392527... e permaneceram nos mesmos processos durante a verificação. As quatro consultas de auditoria verificadas responderam HTTP 200, incluindo histórico de ciclos, indicadores e reconciliação. O ciclo iniciado às 20:56:10 avançou da busca XML para o inventário SFTP, com 3.246 reconhecidos/38 rejeitados; HTTP 401 da ESL reapareceu às 20:58:20. A captura de threads confirmou leitura SFTP durante o trecho inicial sem atualização do painel; os zeros iniciais eram valores parciais, não inventário vazio.

Houve um defeito operacional na retomada do supervisor já ativo: o PM2 registrou a saída da instância anterior depois de iniciar a nova, programou outra partida em sessenta segundos e deixou os PIDs **40320 e 51540** vivos para o mesmo cadastro 190. A listagem do PM2 mostrava apenas 51540. Os dois tinham o JAR novo e o modo noturno. A ordem dos eventos e a implementação local de `God.handleExit` do PM2 7.0.1 são compatíveis com uma corrida entre a saída antiga e o cadastro substituído; não houve falha de inicialização Java. O lock de reconciliação coordena o trabalho no banco, mas não impede a existência das duas JVMs.

Após autorização explícita do usuário, foi removido **somente o cadastro noturno**, encerrada a cópia remanescente por PID com identidade e horário de criação reconferidos, e confirmada a ausência de qualquer JVM noturna antes de iniciar novamente pelo ecosystem existente. A nova instância é **cadastro 191 / PID 28340**, iniciada às **21:01:08** e pronta às **21:01:29**, com zero reinícios. API e worker foram preservados, assim como logs, JAR e banco. Não foi alterada a instalação global do PM2.

Para futuras trocas neste ambiente, evitar a retomada direta de um supervisor já online: separar a parada, confirmar o encerramento das instâncias e só então iniciar uma vez pelo ecosystem principal. O comando de `startOrRestart` anteriormente recomendado não detectou essa duplicação. Evidências do acompanhamento em `target/aceite-rastreio-20260912/`, incluindo eventos PM2, identidades das JVMs, consultas da API e registro da correção autorizada.

**Aceite final às 21:06:** supervisor único PID 28340, zero reinícios, cadastro salvo e conferido no dump PM2, aguardando 02:00–06:00. Histórico noturno ainda vazio, como esperado. API PID 22560 segue respondendo. Worker encerrou o ciclo às **21:05:04**, em **8m54s**, com `FALHA/XML_RETIDO`: onze XMLs avaliados (um já processado, dez retidos por acesso), zero XML enviado; um comprovante pendente, zero envio e zero erro de comprovante. Os 25 timeouts permanecem retidos. O processo do worker terminou e o PM2 aguarda sua próxima partida estimada para **21:35:04**; o PID que ainda aparece em `waiting restart` não está vivo no Windows.

Saldo atual: XML **20 pendentes + 59 bloqueados + 465 a conferir**; comprovantes **um pendente + 143 bloqueados**. A recuperação alcançou mais dez inventários XML, mas o acesso recusado impediu enviá-los: a redistribuição entre pendentes e bloqueados não é confirmação de entrega. Conclusão e fotos da API/runtime em `target/aceite-rastreio-20260912/conclusao.json`. JAR A4392527... preservado; nenhuma alteração Java ou nova bateria necessária neste aceite operacional. A suíte de 430 aprovados continua sendo a validação do pacote em execução.

## Por que o painel passou a mostrar vinte falhas XML

Conferência às **21:10–21:12**, cruzando API, logs e consulta SQL somente leitura com rollback: são **vinte CT-es distintos**, todos com uma avaliação registrada, divididos em dez no ciclo encerrado às 20:06:25 e outros dez no ciclo encerrado às 21:05:04. Não houve novo ciclo após 21:05; o worker aguarda 21:35.

Dos vinte registros, **dois** têm `ORIGEM_XML_HTTP_401` (logs 8818 e 9003) e **dezoito** têm `ORIGEM_XML_AUTENTICACAO_EM_ESPERA`. Em cada ciclo, a primeira consulta ESL foi recusada e as nove avaliações seguintes foram retidas sem nova consulta ESL por causa do intervalo de espera. Não houve envio XML à Vedacit nesses ciclos.

O resumo do período conta vinte documentos classificados como erro; a linha de cada ciclo mostra seus dez. A legenda genérica de falha inclui a espera por acesso, razão pela qual o número pode crescer à medida que outros integrantes dos 79 XMLs já pendentes são avaliados. O saldo continua **20 + 59 = 79**. Da mesma forma, `xmlPendentes=0` no resultado do ciclo não representa fila vazia: essas avaliações entraram em `xmlErros`, enquanto o indicador de saldo atual as classifica como pendência recuperável.

Permanece a pendência de clareza do painel: separar recusa de download, espera por autorização e recusa de envio no destino sem alterar contagens/datas/aceites por suposição. Nenhum contador foi zerado, processo reiniciado ou acesso externo provocado nesta conferência. SQL versionada em `database/sql/diagnostico/20260912_explicar_20_falhas_xml.sql`; evidências em `target/acompanhamento-xml-20260912-211021/`. API estável, supervisor único PID 28340/zero reinícios.
