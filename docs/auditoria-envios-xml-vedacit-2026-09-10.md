# Auditoria de volume e XML Vedacit — 10/09/2026

O principal problema encontrado é a falta de um fluxo operacional de envio dos novos XMLs. O processo periódico observado executa exclusivamente canhotos SFTP e exige um status anterior de sucesso de XML/dados. A conciliação com a planilha do usuário revelou também uma falha reproduzível: esse status pode ser atribuído a um comprovante rejeitado sem envio de XML. Há ainda um limite efetivo de dez itens por ciclo, mas ele não foi atingido em nenhum ciclo de 09/09 ou no recorte de 10/09. Aumentar apenas esse limite não resolve essas duas causas.

## Planilha de XMLs informados como ausentes na Vedacit

A relação `falta_XML_vedacit.xlsx`, fornecida durante a auditoria, contém 769 linhas com 769 NF-es e 769 CT-es distintos; todas as chaves estão preservadas como texto e têm 44 dígitos. A nova leitura completa do SFTP entre 17:55 e 17:57 cruzou o CT-e e a NF-e de cada linha com o conteúdo XML, sem confiar somente no nome do arquivo.

| Classificação exclusiva das linhas da planilha | Notas |
| --- | ---: |
| XML correlacionado no SFTP, sem sucesso de XML na auditoria | **618** |
| Sem CT-e reconhecido nos XMLs do SFTP e sem sucesso local | **77** |
| Vedacit informa ausência, mas há `status_dados=SUCESSO` local | **74** |
| Total | **769** |

Dos 618 casos com XML e sem sucesso local, 352 também possuem comprovante por metadados e estão bloqueados na origem; 259 não possuem qualquer auditoria para o CT-e. Entre os 77 sem XML e sem sucesso, 74 não têm auditoria. Ao todo, 333 CT-es da planilha não possuem auditoria. Os números são subconjuntos, não devem ser somados como demanda adicional.

Dos 74 casos divergentes, 46 têm XML correspondente no SFTP e 28 não. Assim, a disponibilidade física na planilha inteira é de 664 com XML e 105 sem CT-e reconhecido nos arquivos. O sucesso local precisa ser conciliado com a informação do usuário sobre a Vedacit; não é razão suficiente para descartar esses 74 nem para reenviá-los automaticamente.

Na segunda leitura, os XMLs permaneceram em 1.496 arquivos/1.476 CT-es e 1.115 sem sucesso local. Um novo registro foi materializado pelo worker entre as sondagens: os CT-es sem qualquer auditoria passaram de 420 para 419, e os pares bloqueados de 774 para 775, dos quais 699 com XML correlacionado. Isso é evolução do ambiente ativo durante a auditoria. Os 20 arquivos não reconhecidos foram classificados mais precisamente: não possuem exatamente um `infCte` no namespace fiscal; nenhum apresentou erro de parse XML.

## Falha reproduzida: sucesso de XML sem envio

A consulta dirigida dos 74 CT-es divergentes encontrou 52 com sucesso ativo de dados **sem `data_processamento_dados` em qualquer registro de sucesso** e sem sucesso arquivado. Os outros 22 têm data de etapa entre 08/08 e 18/08. A ausência de data, isoladamente, não prova falha de envio; por isso foi conferido também o comportamento do código.

`EtlRepescagemService.sincronizarInventarioSftpVedacit(String, VedacitSftpInventory)` atribui `status_dados=SUCESSO` ao auditar um comprovante rejeitado, inclusive upload ainda instável, sem enviar XML. Quando o arquivo passa a estável, `promoverCandidatoSftpComDadosConfirmados` usa esse status para classificá-lo como `PENDENTE_ENVIO`. O reprocessamento de canhoto chama o destino com os dados tratados como já integrados e pula o XML.

Uma reprodução isolada com as classes compiladas do candidato equivalente ao JAR operacional, repositório inteiramente em memória e nenhuma conexão externa confirmou:

1. Comprovante instável: dados `SUCESSO`, classificação `PENDENTE_TECNICO`.
2. Mesmo comprovante estável: dados `SUCESSO`, classificação `PENDENTE_ENVIO`.
3. Nenhuma etapa XML executada e nenhuma data de processamento de dados.

Portanto, há um caminho comprovado de falso sucesso de XML e liberação indevida de canhoto. Ele é uma explicação possível para parte dos 52 casos sem data, mas o histórico disponível não permite atribuir os 52 individualmente a esse caminho. Os 22 com data também exigem conciliação de aceite com a Vedacit. A reprodução está em `rejected-receipt-state-probe.json`, com `false_xml_success_reproduced=true`; o defeito não foi corrigido nesta auditoria.

Consequentemente, os 1.115 CT-es sem status local de sucesso constituem a fila detectável por ausência dessa flag, não um inventário definitivo de tudo que falta na Vedacit. Os CT-es marcados como sucesso também podem conter divergências, conforme a planilha demonstra.

## Planilha de comprovantes disponíveis na ESL e ausentes na Vedacit

A relação `falta_comprovante_vedacit.xlsx` contém 798 NF-es distintas, com chaves válidas; todas têm a coluna `ESL Comprovante=Sim`. Essa disponibilidade na ESL é informação da planilha fornecida, não resultado de novas consultas à API. O cruzamento foi refeito entre 18:03 e 18:05 com ambas as planilhas e inventário remoto completo.

| Classificação exclusiva da relação de comprovantes | NF-es |
| --- | ---: |
| Um CT-e com comprovante no SFTP, sem sucesso local de canhoto; todas bloqueadas por dados/XML | **695** |
| Sem comprovante correspondente à NF-e no inventário SFTP | **101** |
| Com sucesso histórico local de canhoto, divergindo da ausência informada | **2** |
| Total | **798** |

Dos 695 comprovantes bloqueados, **629 já têm também XML que referencia o par NF-e/CT-e exato**. Outros 66 têm comprovante no SFTP, mas não XML correlacionado entre os CT-es reconhecidos. Dos 101 sem comprovante no SFTP, 71 não têm qualquer auditoria Vedacit por NF-e e 30 possuem histórico; não há timeout ativo ou bloqueio de destino nesse conjunto. O worker exige SFTP para canhoto e não consulta a ESL, portanto a indicação `Sim` na planilha não basta para que essas 101 notas entrem no processamento atual.

As duas notas com sucesso local possuem um CT-e auditado cada e um comprovante SFTP por NF-e. Devem ser conciliadas com o destino antes de reenvio. Em toda a relação, não foi encontrada NF-e com comprovantes SFTP de CT-es diferentes simultaneamente; porém uma das 695 possui mais de um CT-e no histórico de auditoria. A seleção futura deve continuar usando o par exato, porque a planilha não identifica o frete pelo CT-e.

Há 321 NF-es em ambas as planilhas: 314 pertencem ao grupo dos 695 comprovantes bloqueados e sete ao grupo sem comprovante SFTP. As demandas de XML e canhoto se sobrepõem e não devem ser somadas como fretes adicionais.

Foram geradas duas cópias Excel com a classificação por linha e colunas de evidência, preservando os arquivos originais: `falta_XML_vedacit_auditado.xlsx` e `falta_comprovante_vedacit_auditado.xlsx`, em `target/auditoria-envios-20260910/`. Presença de comprovante significa metadado/nome correspondente; não houve download das imagens nem validação de envio SOAP. “Sucesso histórico” é um estado local, sujeito às divergências descritas neste relatório.

## Escopo e evidências

Leitura inicial entre 17:42 e 17:49 BRT de 10/09, complementada até 18:05 após o recebimento das duas planilhas. Foram examinadas integralmente as 21.738 linhas do log consolidado do worker, de 21/08 às 13:03 até 10/09 às 17:24, os argumentos/configurações operacionais, o código dos fluxos e do gráfico, os agregados e identificadores técnicos de `SATELITE_TMS_AUDITORIA` e o SFTP exclusivo Vedacit. O período anterior a 21/08 foi conferido pela API do gráfico e pelo histórico SQL; o log consolidado disponível não cobre integralmente esse período.

O SFTP foi acessado com a fingerprint já configurada, validação dos diretórios e apenas operações de leitura. Os 1.496 arquivos XML foram lidos em memória, com limite individual, conferência de estabilidade e parser sem DTD/entidades externas. Cada passagem leu 13.437.544 bytes; foram três passagens, pois as relações foram fornecidas sucessivamente, sem persistir bytes dos XMLs. As consultas SQL foram exclusivamente SELECT, com timeout e rollback, sem inicializar Spring. Não houve ESL, SOAP, migração, escrita SQL, alteração remota, mudança de configuração ou reinício de processo. As cópias anotadas preservam as chaves já presentes nas planilhas fornecidas e ficam somente na saída local ignorada pelo Git.

O JAR operacional permanece `EBC700C71B264A50EC933FDD290B3761DBB915152420AE5D6626746ADB7F60E2`. A API passiva observada no Windows iniciou em 09/09 às 23:33. O snapshot PM2 salvo em 10/09 às 00:55 contém a API passiva e `WORK-SFTP-CLIENTES`, sem worker XML separado; os ciclos recentes do log corroboram essa configuração. O snapshot salvo não foi apresentado como consulta instantânea do daemon.

## XMLs disponíveis e ausência de confirmação local

| Resultado da conciliação | Quantidade |
| --- | ---: |
| Arquivos `.xml` regulares no SFTP | 1.496 |
| CT-es distintos identificados no conteúdo XML | 1.476 |
| CT-es com algum `status_dados=SUCESSO` no histórico, incluindo arquivados | 361 |
| CT-es disponíveis sem confirmação local de sucesso de dados | **1.115** |
| Desses 1.115, CT-es sem qualquer registro na auditoria Vedacit | **420** |
| Arquivos fora do formato CT-e reconhecido pela sondagem | 20 |

Os 20 arquivos não reconhecidos não foram contados como CT-e pronto: a sondagem exige exatamente um `infCte` no namespace fiscal, identificador de 44 dígitos e modelo 57. A passagem complementar confirmou ausência de `infCte` único no namespace fiscal, sem falha de parse; ainda é necessário identificar o tipo documental antes de decidir seu tratamento. Nenhum arquivo apresentou falha de leitura ou alteração durante a leitura.

Dos 1.115 CT-es sem confirmação, 736 têm mês de emissão 08/2026 na chave e 379 têm 09/2026. A distribuição de `tpCTe` extraída foi `0=1.081`, `1=33`, `3=1`; a existência do arquivo não substitui a validação das regras de envio de cada tipo. Os 420 sem auditoria estão incluídos nos 1.115, não são um total adicional.

“Sem confirmação local” significa ausência de `status_dados=SUCESSO` para o CT-e em toda a auditoria consultada, inclusive arquivados. Não houve consulta de recebimento à Vedacit; portanto, este número não prova ausência no destino nem autoriza reenviar timeouts ambíguos. A conciliação verificou estrutura e correlação, não assinatura fiscal, situação de cancelamento, aceite do destino ou ocorrência ESL 110.

## Efeito sobre os canhotos

Na leitura SQL havia 774 NF-es distintas, cada uma com par NF-e/CT-e válido, classificadas como `BLOQUEADO_ORIGEM` e com `status_dados=PENDENTE_ORIGEM`. O cruzamento posterior encontrou:

| Situação desses 774 pares | Quantidade |
| --- | ---: |
| Com comprovante correspondente no inventário SFTP | 774 |
| Com XML cujo conteúdo referencia exatamente o CT-e e a NF-e, além do comprovante | **698** |
| Sem XML correlacionado entre os CT-es reconhecidos na leitura | 76 |

Os 698 comprovantes foram confirmados por metadados e nome; não foram baixadas imagens para testar conversão ou aceite SOAP. Não se deve converter automaticamente os 698 em novos envios: três dos 774 bloqueados possuem outro registro ativo de sucesso completo para o mesmo par exato. Isso revela uma pendência de conciliação de estado que deve ser resolvida preservando o sucesso existente. Outros oito bloqueados possuem dados em sucesso para a mesma NF-e com CT-e diferente, vínculo insuficiente para liberação automática.

O quadro completo de registros ativos Vedacit na leitura SQL era: 2.260 em sucesso completo, 774 bloqueados aguardando dados, 38 rejeições de nome sem par fiscal válido, um bloqueio de destino, 19 timeouts ambíguos e um `PENDENTE_ENVIO`. São registros de auditoria, não contagem equivalente de arquivos ou NF-es distintas. A soma dos bloqueios exibidos no último ciclo era 813, incluindo os 38 nomes rejeitados e o bloqueio de destino.

A única pendência normal restante tem NF-e presente no inventário, mas não possui comprovante para seu CT-e exato. O log repete essa mesma NF-e 45 vezes em 09/09 e 34 vezes no recorte de 10/09, sempre com comprovante indisponível e sem consulta ESL. Isso explica o saldo recorrente de um, que não representa toda a demanda bloqueada. A existência de outro CT-e da mesma NF-e exige reconciliação documental; não permite trocar a chave por suposição.

O inventário remoto avançou durante a auditoria: o último ciclo tinha 3.098 arquivos válidos e 38 rejeitados; a leitura direta posterior encontrou 3.099 válidos e 39 entradas rejeitadas pela política de nome/tipo/tamanho/estabilidade. As leituras SQL/SFTP não constituem um snapshot distribuído atômico.

## Por que os XMLs não avançam

- `WorkSftpClientesRunner.executarCiclo` exige `VEDACIT_SFTP_RECEIPT_ONLY=true` e chama somente `processarClienteSftpVedacit`.
- `EtlRepescagemService.sincronizarInventarioSftpVedacit` reaproveita confirmação histórica de dados. Para arquivo novo sem confirmação, materializa `PENDENTE_ORIGEM`/`BLOQUEADO_ORIGEM`; não envia seu XML.
- A fila normal exige dados em sucesso antes do canhoto. Seu saldo conta apenas candidatos dessa fila, deixando os bloqueios fora.
- O leitor `VedacitSftpClient.buscarXmlCte` existe, mas não é acionado pelo worker de canhotos. O fluxo geral XML por evento 110 depende do orquestrador; o scheduler e o ciclo único geral estão desligados nos argumentos dos processos observados, e a API está em modo passivo.
- A última etapa de dados Vedacit registrada como sucesso, incluindo histórico arquivado, é de **20/08 às 14:28:12**. A última etapa com erro é de 20/08 às 15:00:20. Isso é consistente com a ausência do fluxo XML observado, sem inferir atividade externa não auditada.

Referências: `WorkSftpClientesRunner.java:45`, `EtlRepescagemService.java:119`, `EtlRepescagemService.java:202`, `VedacitSftpClient.java:91`, `OrquestradorEtlScheduler.java:9`, `ecosystem.config.js`.

## Limites, paginação e cronologia

O `.env` mantém `SFTP_RODOGARCIA_MAX_FILES_PER_CYCLE=10`, referenciado pelo perfil `SFTP_CLIENT_VEDACIT_MAX_FILES_PER_CYCLE`. O runner aplica o menor valor entre o perfil e `WORK_SFTP_CLIENTES_MAX_ITEMS` (padrão 100). O histórico operacional registra que esse ajuste de 100 para 10 foi feito em 07/09 para acompanhar o primeiro lote após a correção SQL. O código atual processa uma seleção e encerra, mesmo havendo saldo; o PM2 espera 30 minutos após o encerramento para iniciar outra execução. Há pausa de um segundo entre itens por padrão, além do tempo das chamadas SOAP.

Não foi encontrado contador de cota diária nesse fluxo. Dez é limite por ciclo, não por dia. Ainda assim, com mil itens elegíveis seriam necessários pelo menos 100 ciclos: aproximadamente 49h30 apenas entre ciclos, além de inicialização, inventário e processamento. Portanto, a implementação atual não atende à expectativa de drenar todo o estoque em sequência com apenas as pausas necessárias.

O particionamento SQL em blocos de 500 percorre a lista inteira, une os resultados e só aplica o limite de processamento ao final. Ele não limita o inventário nem descarta páginas. Separadamente, `INTEGRATION_MAX_PAGES_PER_CYCLE=10` no orquestrador ESL determina a pausa de paginação; o loop retoma após a pausa. Esse orquestrador não é o caminho operacional do worker SFTP observado. Referências: `EtlRepescagemService.java:734`, `EtlFluxoDestinoService.java:330`.

O log completo encontrou 554 falhas por excesso de parâmetros SQL entre **26/08 às 12:22 e 07/09 às 11:46**, além de cinco timeouts de conexão nesse intervalo. Após a correção entrar no ciclo das 12:25 de 07/09, o saldo caiu de 222 para dois até 08/09 às 03:59. Novas entradas elegíveis continuaram sendo enviadas; a grande maioria das novas pendências ficou bloqueada na etapa de dados.

| Data do término do ciclo, BRT | Ciclos auditados | Envios contabilizados pelo worker | Maior seleção por ciclo | Bloqueios no último ciclo do dia/recorte |
| --- | ---: | ---: | ---: | ---: |
| 07/09 | 41, sendo 24 falhas anteriores à correção | 168 | 10 | 628 |
| 08/09 | 44 | 87 | 10 | 710 |
| 09/09 | 45 | 10 | 6 | 780 |
| 10/09 até 17:24 | 34 | 4 | 4 | 813 |

Os totais de envios coincidem entre o log consolidado e a auditoria agrupados pela data do **término**. Relatórios anteriores que agruparam pelo início de ciclo apresentam 178 em 07/09 e 77 em 08/09: um ciclo de dez itens atravessou a meia-noite. O status legado `CONCLUIDO` também podia coexistir com erro individual; o log confirma dois erros em 08/09. Em 10/09 houve um timeout de canhoto e o ciclo recebeu `FALHA`, com métricas preservadas nesse caminho.

## O gráfico não mede exclusivamente envios diários

A API reproduziu o desenho enviado: 171 sucessos em 07/09, 86 em 08/09, dez em 09/09 e seis em 10/09 no momento da consulta. A consulta `LogIntegracaoRepository.buscarEvolucaoDiariaIntegracoes`:

1. Agrupa pelo `data_processamento` atual do registro, não por um evento imutável de envio.
2. Considera sucesso quando qualquer um de status geral, dados ou canhoto indica sucesso.
3. Exclui arquivados e bloqueios SFTP de origem/destino.
4. Retorna apenas dias com registros, sem preencher os dias ausentes com zero.

Em 10/09, os seis “sucessos” são quatro canhotos em sucesso, um canhoto com timeout e um pendente sem comprovante; todos possuem dados em sucesso. Em 08/09 os 86 incluem dois timeouts de canhoto. Por isso a linha de erros pode ficar em zero enquanto há trabalho bloqueado e falhas parciais. A diferença entre gráfico e contador de ciclos é consequência de critérios distintos e atualização do estado, não prova de envio perdido.

## Encaminhamento técnico

Priorizar a correção do falso sucesso de XML, a recuperação auditável dos XMLs disponíveis e a conciliação dos sucessos existentes; depois liberar os canhotos pelos critérios normais. A relação fornecida já aponta 618 XMLs disponíveis sem sucesso e 629 comprovantes bloqueados com XML correlacionado, com sobreposição entre os grupos. Os 1.115 CT-es do inventário ampliado exigem uma prévia por documento que separe ausência de tentativa, falha recuperável, timeout e eventual confirmação externa, preservando o contrato de eventos e a idempotência. Os 419 sem auditoria na leitura complementar precisam entrar nessa prévia, pois não aparecem na fila de comprovantes. Para os 101 comprovantes presentes na ESL segundo a planilha e ausentes no SFTP, definir recuperação dirigida/abastecimento SFTP preservando a correlação com CT-e e a política de origem; não ativar fallback global por consequência desta auditoria.

Em seguida, evoluir o worker para continuar lotes enquanto houver candidatos elegíveis, preservando intervalo, backoff, locks e uma tentativa por documento em cada passagem. Os 30 minutos devem ser avaliados como espera quando a fila ficar ociosa; aumentar somente de dez para cem conserva a interrupção artificial entre lotes e não resolve XML bloqueado.

O painel deve distinguir XML, canhoto, bloqueio e timeout, com contagem de envios baseada em eventos/etapas e exibição explícita da demanda bloqueada. Também devem ser conciliados os três bloqueios com sucesso ativo e a pendência repetida sem par CT-e/comprovante. Esta entrega é uma auditoria: nenhuma dessas correções ou recuperações foi executada.

## Artefatos locais

- `target/auditoria-envios-20260910/database-readonly.json`: agregados, classificações e ciclos recentes.
- `target/auditoria-envios-20260910/database-additional-readonly.json`: última etapa de XML, composição do gráfico e bloqueios com sucesso ativo.
- `target/auditoria-envios-20260910/logs-parsed.json`: leitura integral do log com resumos, horários e números de linha, sem documentos.
- `target/auditoria-envios-20260910/xml-sftp-readonly.json`: conciliação dos XMLs e metadados de comprovantes.
- `target/auditoria-envios-20260910/planilhas-cruzadas-readonly.json`: conciliação final por linha das duas planilhas, com sufixos de chaves e contagens.
- `target/auditoria-envios-20260910/spreadsheet-success-evidence.json`: classificação dos 74 sucessos de XML divergentes, sem chaves fiscais.
- `target/auditoria-envios-20260910/rejected-receipt-state-probe.json`: reprodução em memória do falso sucesso de dados.
- `target/auditoria-envios-20260910/falta_XML_vedacit_auditado.xlsx` e `falta_comprovante_vedacit_auditado.xlsx`: cópias anotadas para revisão das linhas originais.
- `target/auditoria-envios-20260910/chart-api.json`, `daily-comparison.json` e `pm2-snapshot-sanitized.json`.
- Sondagens locais: `target/auditoria-envios-20260910.ps1`, `target/auditoria-envios-20260910/logs-readonly.ps1` e `target/auditoria-envios-20260910/XmlSftpAudit.java`. Saídas em `target` são temporárias e ignoradas pelo Git; o relatório preserva os resultados agregados.
