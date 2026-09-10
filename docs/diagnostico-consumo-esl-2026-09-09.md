# Diagnóstico de consumo ESL — 09/09/2026

O consumo elevado de `/graphql` mostrado no painel é compatível com as varreduras do processo `ETL-EXTRACAO-DADOS-LOOP`, especialmente o cadastro de usuários (`individual`). O worker Vedacit está em modo exclusivo de canhotos SFTP e não usa esse endpoint. Remover o fallback documental do Satélite não resolve esse consumo GraphQL.

## Evidências verificadas

A investigação cruzou os prints fornecidos, os processos PM2 existentes, os logs reais, o artefato do Satélite e consultas exclusivamente `SELECT` em `SATELITE_TMS_AUDITORIA`. A documentação local foi tratada como contexto, sem presumir que descrevesse o runtime atual.

| Evidência | 05/09/2026 | 08/09/2026 |
| --- | ---: | ---: |
| Painel ESL: usuário API Desenvolvedor, `/graphql` | 28.006 requisições | 22.342 requisições |
| Marcos de progresso GraphQL de usuários no extrator | 533 | 411 |
| Marcos de progresso GraphQL de fretes no extrator | 18 | 26 |
| Varreduras de usuários encerradas no limite de 2.000 páginas | 4 | 3 |
| Alertas `Thread leak detectado` na operação de usuários | 11 | 10 |
| Ciclos Vedacit registrados na auditoria SFTP | 46 com falha | 43 concluídos |
| Envios registrados nesses ciclos Vedacit | 0 | 77 |

O extrator registra progresso a cada 50 páginas. Os marcos observados correspondem aproximadamente à ordem de 27,5 mil páginas em 05/09 e 21,8 mil em 08/09, compatível com o volume do painel. Esses valores **não são uma contagem exata de requisições**: existem páginas entre os marcos, varreduras interrompidas, fronteiras de dia, consultas menores e eventuais retentativas. Cada página exige uma chamada GraphQL; uma chamada não equivale a uma entrega.

Só as finalizações registradas de usuários em 08/09 somam 6.014 páginas; as varreduras interrompidas também consumiram chamadas, mas não necessariamente emitiram um resumo final. Por isso, somar apenas os resumos subestima o consumo. Os alertas de threads ainda ativas após timeout também constam dos logs reais. Não foi atribuída automaticamente toda a contagem da conta ESL a um único processo: falta o detalhamento de requisições do provedor por token/origem para fechar as 22.342 individualmente.

## Conferência numérica dos dois dias do painel

| Data | Painel ESL: chamadas `/graphql` do Desenvolvedor | Estimativa pelos marcos do extrator | Painel menos estimativa | Diferença sobre o painel |
| --- | ---: | ---: | ---: | ---: |
| 05/09/2026 | 28.006 | aproximadamente 27.550 | 456 | 1,63% |
| 08/09/2026 | 22.342 | aproximadamente 21.850 | 492 | 2,20% |

A conta usada é explícita: em 05/09, `(533 marcos de usuários + 18 de fretes) × 50 = 27.550`; em 08/09, `(411 + 26) × 50 = 21.850`. A parcela estimada de usuários é 26.650 e 20.550, respectivamente; a de fretes é 900 e 1.300. Essa comparação mostra proximidade de volume, não uma conciliação individual nem a comprovação de que 98% das requisições pertencem ao processo.

O log é emitido **antes** da busca das páginas 50, 100, 150 etc.; portanto, multiplicar os marcos por 50 é uma aproximação. Páginas finais, consultas com menos de 50 páginas, viradas de dia e retentativas não são resolvidas por essa multiplicação. Os resumos também registram 23 páginas de coletas em 05/09 e 124 em 08/09, operação fora da estimativa acima. As diferenças de 456 e 492 não foram atribuídas artificialmente a retentativas ou a outro consumidor.

Na extração dos marcos, não foram encontrados eventos exatamente duplicados nem números de página fora dos múltiplos de 50. Foram lidos somente os arquivos de runtime, usando as datas internas das linhas; os logs de console e de operações não foram somados, evitando contar a mesma mensagem em arquivos diferentes. Os CSVs de auditoria do daemon registram duração, registros e status, mas não um contador individual de requisições. Para fechar cada chamada do painel, falta uma trilha individual com data, rota e consumidor; os arquivos examinados não a fornecem.

## Origem dos dados e fallback Vedacit

- `Satelite-API-19090`: processo passivo, com `APP_DASHBOARD_API_ONLY=true`, scheduler, ciclo único, repescagem e retry noturno desabilitados nos argumentos efetivos do PM2.
- `WORK-SFTP-CLIENTES`: execução periódica com intervalo de reinício de 30 minutos, somente Vedacit habilitada, scheduler geral desligado e `VEDACIT_SFTP_RECEIPT_ONLY=true`. Na consulta ao PM2 estava aguardando o próximo ciclo, comportamento esperado nesse modo.
- O caminho efetivo do worker lista o inventário SFTP, seleciona a auditoria técnica e reprocessa o canhoto com os dados/XML tratados como já enviados. O serviço não busca ocorrências ESL nesse caminho e bloqueia o fallback de canhoto. Em 08/09 e 09/09 há mensagens explícitas: `Canhoto indisponível no SFTP (lote exclusivo; ESL não consultada)`.
- Os caminhos ESL do Satélite são REST: `/api/customer/invoice_occurrences`, `/api/freight_delivery_receipts` e `/api/ctes`. Não há rota `/graphql` no cliente nem nas 1.238 entradas próprias examinadas no JAR atual. O hash do JAR permaneceu `A77DB9AD8428E5B47553955D0534EB3F83B7511B5E9404A2EB17BE8EEB937751`.
- A tabela `dbo.tb_esl_request_telemetria` tem 19.144 registros históricos, com último evento em 20/08/2026 às 21:03:49 UTC. Não há registros entre 05 e 09/09. A ausência de telemetria isoladamente não provaria ausência de chamadas; aqui ela foi cruzada com os argumentos do PM2, o caminho do código e os logs de operação.

Portanto, não é correto interpretar o painel como “22 mil entregas buscadas pela Vedacit”. O fluxo geral do Satélite possui leitura de ocorrências por API, mas o worker Vedacit atualmente observado trabalha com os arquivos SFTP. Esta investigação de consumo não comprova que todas as entregas existentes na ESL chegaram ao SFTP ou foram aceitas pelo destino; essa completude requer conciliação específica por documento/evento.

## Mudança já observada no extrator

O JAR `etl-dash/etl-extracao-dados/target/extrator.jar` foi modificado em 08/09 às 20:24:11, e o PM2 informa início do processo atual às 21:06:50. Depois desse início, os dois resumos de usuários de 08/09 mostram 7 páginas cada.

Em 09/09, até os logs disponíveis por volta de 20:49, foram observadas:

| Operação GraphQL | Finalizações com resumo | Páginas somadas nos resumos |
| --- | ---: | ---: |
| Usuários | 17 | 53 |
| Coletas | 15 | 775 |
| Fretes | 15 | 3.523 |

Não apareceram alertas de vazamento de threads da operação de usuários nos logs de 09/09 examinados. Isso demonstra uma redução no trabalho registrado dessa operação após a atualização; não constitui garantia sobre todos os próximos ciclos nem sobre o total diário final do painel ESL. O extrator continua consumindo a API para suas outras operações.

Na nova leitura, com último resumo de fretes às **22:04:20 de 09/09**, os totais passaram a 60 páginas de usuários em 18 resumos, 837 de coletas em 16 resumos e 3.738 de fretes em 16 resumos: **4.635 páginas somadas em execuções com resumo**. Esse é um recorte parcial do dia e não uma contagem de todas as requisições HTTP. Naquele momento ainda não havia sido fornecido o painel de 09/09; a comparação posterior está abaixo.

## Painel de 09/09 fornecido posteriormente e data da correção

O novo print do usuário mostra **4.641 chamadas `/graphql` do API Desenvolvedor em 09/09**, contra 22.342 em 08/09: menos 17.701 chamadas, ou **79,23% abaixo no acumulado exibido**. O dia 09/09 ainda está em andamento; esse percentual não é um fechamento diário nem uma comparação normalizada por horário.

Os 4.635 registros de páginas somados nos resumos ficam 6 abaixo do painel, uma diferença numérica de **0,129%**. A proximidade reforça a atribuição ao extrator, mas páginas e tentativas HTTP não são contadores idênticos. A busca adicional encontrou 11 mensagens de falha/retentativa GraphQL no dia; portanto, não se atribuiu artificialmente a diferença de seis a seis retries nem se declarou conciliação individual fechada.

A correção foi aplicada na **terça-feira, 08/09/2026**. O JAR foi gerado às **20:24:11**, e a execução atual começou às **21:06:50, horário de Brasília**, confirmada pelo snapshot PM2 e pelo log de início às 21:06:51. O Git registra posteriormente o commit **`a602b75` às 21:48:34**, com filtro incremental `updatedAt` na extração de usuários e correção do sucesso indevido ao atingir limite de páginas. A data do commit é o registro no Git, não o horário de entrada em execução.

O percentual de 79,23% refere-se apenas a `/graphql`. As linhas de relatórios analíticos do API Desenvolvedor visíveis no print de 09/09 somam outras 3.795 chamadas; as linhas visíveis desse usuário totalizam 8.436. O total geral do painel reúne também Raster e clientes e não deve ser usado como se fosse consumo exclusivo do extrator.

## Decisão indicada

Preservar o fallback do Satélite nesta investigação. O fallback de canhotos já está bloqueado no worker Vedacit, e os acessos GraphQL vêm de outra integração. Conferir o fechamento de consumo após a atualização do extrator, principalmente em 09/09, e concentrar eventual ajuste de frequência, paginação e retentativas no `ETL-EXTRACAO-DADOS-LOOP`.

Não foram alterados código de produção, `.env`, credenciais, flags, agendamentos ou processos. Nenhuma nova requisição ESL, transferência SFTP ou publicação ao cliente foi feita neste diagnóstico. As consultas SQL foram somente de agregados, na base autorizada; nenhum dado foi alterado e nenhuma outra database foi consultada.

## Evidências locais reproduzíveis

- `target/unit-tests/esl-comparacao-painel-09-09-2026.json`: números do novo print, diferenças e horários confirmados da correção.
- `target/unit-tests/conciliacao-consumo-esl-20260909.json` e `.csv`: valores do painel, contagem de marcos, fórmula e diferenças.
- `target/unit-tests/graphql-progress-detail-20260909.json`: marcos com data/hora, contexto de execução, entidade e página, sem payloads ou credenciais.
- `target/unit-tests/esl-usage-db-readonly-20260909.json`: agregados de telemetria e ciclos SFTP.
- `target/unit-tests/esl-usage-runtime-20260909.json`: seleção segura dos argumentos e estados PM2.
- `target/unit-tests/graphql-pages-final-20260909.json`: resumos de páginas por operação/data.
- `target/unit-tests/graphql-extraction-summaries-20260909.json`: finalizações GraphQL com quantidade explícita.
- `../etl-dash/etl-extracao-dados/logs/aplicacao/runtime/extrator-esl*.log`: fonte dos marcos de progresso, limites, timeouts e resumos. Foram usadas as datas internas das linhas, pois o nome de um arquivo rotacionado não delimita corretamente todos os eventos contidos nele.
- `logs/work-sftp-clientes-out-28.log`: ciclos Vedacit, falha SQL em 05/09 e confirmação de ausência de fallback de canhoto em 08/09.
