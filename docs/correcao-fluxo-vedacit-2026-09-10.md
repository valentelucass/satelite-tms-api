# Correção do fluxo Vedacit — 10/09/2026

O candidato recupera a sequência **XML confirmado → comprovante**, continua lotes enquanto houver itens elegíveis e impede novas confirmações falsas. O aceite real da Vedacit ainda precisa ser validado na operação.

Configuração aplicada em 10/09 às 21:36 BRT por solicitação do usuário: o cadastro PM2 do `WORK-SFTP-CLIENTES` agora contém XML, SFTP XML, envio de CT-e e dreno habilitados, com três erros consecutivos como limite de interrupção e intervalo XML de 1.000 ms. O worker foi deixado parado (PID 0), com o mesmo nome e novo ID 189, pronto para o start humano. A alteração foi persistida somente na entrada desse worker no dump PM2; outros processos, `.env`, logs e JAR foram preservados. Nenhum envio foi iniciado durante a aplicação. Evidência `target/ativacao-worker-xml-20260910/aplicacao.json`; falta conferir o primeiro ciclo real. Os parágrafos seguintes registram o histórico anterior à aplicação das flags.

Publicação em 10/09 às 21:19 BRT: a pedido do usuário, o código foi recompilado com Java 17 e o JAR foi instalado em `target/satelite-0.0.1-SNAPSHOT.jar`, caminho dos dois processos PM2. SHA-256 atual `6C5976AE48C418D4D8C5E2408E035DA49218B9F604C9D31A3B67234338DCADF6`; 42 testes direcionados adicionais passaram e 1.249 arquivos empacotados foram conferidos. O usuário parou os processos para liberar o arquivo; a IA concluiu a troca com backup, sem reiniciar. Evidência em `target/publicacao-pm2-20260910-2114/instalacao.json`. As flags registradas no PM2 não foram modificadas nesta publicação: ainda é necessário aplicar a configuração revisada e realizar o primeiro lote supervisionado. Os candidatos citados abaixo pertencem às validações anteriores.

Atualização posterior: o candidato mais recente é `target/indicadores-etapas-20260910/satelite-0.0.1-SNAPSHOT.jar`, SHA-256 `45CEF40A497725004A4F141C7E42DF34AD83C809A41A438B817EDF12579ED550`. Inclui este fluxo e o contrato de indicadores separados para o Dashboard. A rodada adicional aprovou 42 testes direcionados, SQL somente leitura e equivalência dos 1.249 arquivos de aplicação do pacote. Publicação conjunta documentada em `../etl-dash/dashboards/docs/correcao-indicadores-etapas-2026-09-10.md` a partir da raiz do repositório. Os pacotes abaixo permanecem como evidências das rodadas anteriores.

## Mudanças

- `EtlRepescagemService`: rejeição de comprovante não atribui sucesso ao XML. Arquivo que estabiliza sem XML continua bloqueado na origem. Confirmações anteriores, recusas e resultados ambíguos são preservados.
- A confirmação ativa do **mesmo par NF-e/CT-e**, inclusive do fluxo XML sem `sftp_cliente`, passa a liberar a fila SFTP. As datas das etapas são copiadas da confirmação original; outro CT-e da mesma NF-e não basta. A materialização também consulta as duas chaves: duas NF-es no mesmo CT-e mantêm comprovantes independentes. Restrições de canhoto do histórico são preservadas mesmo quando o XML está confirmado.
- Dez itens passam a ser o tamanho do lote. O serviço percorre o inventário inteiro em lotes, sem repetir uma NF-e indisponível na mesma passagem. Mantém consultas de até 500 chaves, locks, pausa entre itens e interrupção após três erros consecutivos. `WORK_SFTP_CLIENTES_DRAIN_ENABLED=false` permite um primeiro lote supervisionado.
- `WorkSftpClientesRunner` pode executar a etapa XML antes de materializar os comprovantes. Usa o fluxo existente de **evento 110**, o cursor `VEDACIT_XML` e a política de chamadas ESL; não aciona outros destinos nem o fluxo geral de entrega. O perfil Vedacit precisa estar habilitado. Os limites são validados antes de qualquer integração.
- `ecosystem.config.js` prepara a etapa XML, a fonte SFTP e o dreno para o worker. Como esse arquivo local é ignorado pelo Git, a mesma configuração sem credenciais foi incluída em `ecosystem.config.example.js` para revisão/versionamento. A configuração já carregada pelo PM2 não muda com a edição do arquivo. O operador deve aplicar a configuração revisada na atualização; retomar apenas o cadastro antigo pode manter o XML desligado.
- `VedacitIntegrationService`: sucesso de XML e comprovante exige resposta SOAP positiva. Resposta vazia/sem status não confirma envio. Canhoto sem confirmação fica em `TIMEOUT_AMBIGUO`; o XML em erro permanece retido para conciliação. O XML respeita intervalo de 1 s entre tentativas (`VEDACIT_XML_SEND_INTERVAL_MS`), além do controle da ESL e das pausas de paginação.
- O XML ganha exclusão por CT-e e consulta de sucesso histórico dentro da exclusão. Timeout, conexão interrompida e resposta sem confirmação não geram retry imediato de XML. Sucessos locais divergentes não são apagados nem reenviados automaticamente.
- `VedacitSftpClient` guarda somente identificação e metadados dos XMLs em memória. Outros CT-es já identificados não precisam ser baixados novamente a cada busca. Mudança de tamanho/data invalida o índice; o arquivo selecionado é relido e validado antes do envio. Não há cache de bytes fiscais nem escrita no SFTP.

## Recuperação das planilhas

`scripts/preparar_recuperacao_vedacit.ps1` leu as duas planilhas originais e a auditoria concluída, validou as linhas e produziu em `target/vedacit-correcao-20260910/manifestos/`:

| Arquivo | Quantidade | Tratamento |
|---|---:|---|
| `xml_sem_sucesso_pares.txt` | 695 | 618 XMLs disponíveis no SFTP + 77 sem XML reconhecido. Lista exata NF-e;CT-e para recuperação. |
| `xml_divergencias_conciliar.txt` | 74 | Sucesso local em desacordo com a ausência informada pela Vedacit. Não alimentar reenvio automático. |
| `comprovantes_sem_sftp_nfes.txt` | 101 | Exigem consulta/recuperação dirigida na ESL ou disponibilização dos arquivos no SFTP. Esta correção não implementa um importador de comprovantes ESL para esse grupo. |

Os arquivos são locais e não entram no Git. As planilhas originais permanecem intactas. As relações de XML e comprovante se sobrepõem; não somar seus volumes como fretes distintos.

`RecuperacaoDirigidaVedacitRunner` agora percorre todas as páginas de ocorrências por NF-e, exige evento 110 e respeita o CT-e do manifesto. Uma lista de mil pares não é truncada em 200. Mantém deduplicação por CT-e, intervalo, interrupção após erros repetidos e proteção contra paginação que não avança. Não altera o cursor incremental. Por padrão apenas encontra as emissões; não envia SOAP.

O script `scripts/recuperar_xml_vedacit.ps1` é foreground, recebe `-Manifesto` e `-Jar` explicitamente e não gerencia PM2. Sem `-Enviar`, executa a prévia; `-Enviar -SomentePrimeiroLote -TamanhoLote 10` limita a primeira execução real. A prévia confirma a existência/correlação da ocorrência, não o aceite do XML; as chamadas ESL podem gerar telemetria normal. Não executar simultaneamente ao worker antigo. A recuperação usa SFTP preferencial e o fallback XML ESL já existente, sem liberar o fallback de comprovantes do worker.

## Validação e limites

A simulação de mil candidatos em lotes de dez confirmou 999 envios simulados e uma pendência sem arquivo, tentada somente uma vez. Também foram cobertos falso sucesso por upload instável, preservação de sucesso/timeout/recusa, resposta SOAP vazia, XML antes do comprovante, CT-e exato em página posterior, prévia sem SOAP, índice SFTP e parada por erros consecutivos.

A consulta nova de confirmação ativa foi executada contra `SATELITE_TMS_AUDITORIA` e coincidiu com SQL independente. A mesma sonda verificou 3.021 NF-es, sete blocos por fila, máximo de 502 parâmetros por statement e saldo de um candidato. Foram 37 leituras, com bloqueio de DML/DDL/commit e rollback. Evidência: `target/vedacit-correcao-20260910/sql-readonly.json`.

Foi confirmada localmente a igualdade dos sete campos de conexão/pasta/host key entre o perfil Vedacit e a fonte XML existente, sem exibir credenciais. Não houve envio SOAP, alteração no SFTP, escrita SQL operacional, exclusão de logs nem gerenciamento de processos nesta entrega.

A suíte final teve **327 testes: 324 aprovados, zero falhas/erros e três manuais não executados**, com rede bloqueada. Java 17, compilação dos 1.105 fontes e empacotamento offline concluídos. Os 1.245 arquivos de aplicação dentro do JAR são idênticos por SHA-256 à saída testada; os 273 arquivos de fontes/testes/recursos conferidos permaneceram iguais. Os três proxies SOAP também foram testados com os WSDLs locais, sem modificar classes geradas. Isso não equivale ao aceite remoto da Vedacit.

Pacote anterior, preservado como baseline da suíte completa: `target/vedacit-correcao-20260910/satelite-0.0.1-SNAPSHOT.jar`.

SHA-256: `CD02D0FAF287B6EAE5078290FCF032CF5A1E6E7793BD033C5D735CCEC9B5F940`.

Evidências finais na mesma pasta: `tests-summary.json`, `package-verification.json`, `package.log`, `source-hashes.json`, `sql-readonly-final.json` e `runtime-preservado.json`. A sonda SQL foi repetida com as classes finais e manteve as mesmas contagens e paridade. Saída completa da suíte: `target/unit-tests/coverage-20260910-190115-951/`. Os scripts PowerShell e a configuração PM2 passaram na verificação de sintaxe; os três manifestos foram efetivamente gerados e conferidos.

O JAR operacional continua com SHA-256 `EBC700C71B264A50EC933FDD290B3761DBB915152420AE5D6626746ADB7F60E2`. Às 19:03, a API passiva permanecia no PID 23208, iniciado em 09/09; não havia JVM do worker naquele instante, o que não significa que seu agendamento PM2 esteja parado. Nenhum processo foi iniciado, interrompido ou reiniciado por esta entrega.

## Atualização dos avisos Java

Os cinco avisos informados pelo IDE foram corrigidos: guarda explícita de página nula na recuperação dirigida, coleta dos nomes SFTP com verificação de nulidade e remoção dos três imports redundantes de `ArgumentMatchers`. A compilação Java 17 e os **38 testes direcionados passaram**, sem falhas/erros e com rede bloqueada. A suíte completa citada acima é o baseline anterior; não foi repetida nesta limpeza.

**Candidato atualizado para instalação:** `target/vedacit-correcao-20260910/avisos-java/satelite-0.0.1-SNAPSHOT.jar`.

SHA-256: `8976BDACF3BB3C111C7EA5547C7CC79B4A253B8A7D86A269C19317D4743AD1DD`.

Os 1.245 arquivos de aplicação coincidem com a saída testada `target/unit-tests/coverage-20260910-190928-884/`. Evidências em `avisos-java/`: `tests-summary.json`, `package-verification.json`, `package.log` e `source-hashes.json`. O JAR operacional manteve o hash `EBC700C7...`; nenhum processo foi gerenciado nem houve envio real nesta atualização.

## Aplicação pelo operador

1. Parar os processos do Satélite para substituir o pacote sem concorrência com o JAR antigo.
2. Instalar o candidato validado e aplicar a configuração revisada do worker; a API continua passiva.
3. Executar a prévia dirigida e um lote real supervisionado, conferindo o aceite de XML e comprovante no MultiTMS.
4. Confirmado o lote, liberar o dreno e a recuperação do restante da lista. O cursor incremental cobre a continuidade; a lista dirigida cobre documentos históricos que ficaram antes dele.

Não houve migration: esta entrega não exige modificar a estrutura do banco. A instalação preserva o histórico e não reseta os 74 sucessos divergentes, os timeouts nem recusas. Os 101 comprovantes ausentes no SFTP e as duas divergências de canhoto continuam dependendo de tratamento específico. O gráfico ainda conta estados atualizados, não um diário imutável de transmissões; a correção desse indicador permanece separada.

O controle de runtime permanece humano conforme `../etl-dash/CONTEXTO_GLOBAL.md`, seção 5: “O controle de runtime pertence exclusivamente ao humano.” Não é possível afirmar recebimento real apenas com testes isolados.
