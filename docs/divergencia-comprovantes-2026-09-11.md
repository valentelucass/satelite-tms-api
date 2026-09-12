# Divergência de comprovantes: 965 no painel e redução de 798 para 505

**Atualização final — 11/09/2026, 21:50 BRT:** correção instalada no JAR, no frontend e no banco. 1.462 testes aprovados, além de validações reais SQL/Hibernate/concorrência e oito cenários visuais. API e worker do Satélite permanecem parados pelo operador. O histórico com primeira data incerta está qualificado separadamente, sem datas inventadas; detalhes e manifesto abaixo.

Investigação inicial de 11/09/2026, aproximadamente 20:43–20:56 BRT: somente consultas SQL na base `SATELITE_TMS_AUDITORIA`, GETs locais da API, leitura de arquivos e reprodução em memória. Essa primeira fase não alterou aplicação, banco, configuração, processos ou envios de diagnóstico.

## Conclusão

Foi reproduzida uma regressão no inventário SFTP do Satélite: a recuperação da confirmação histórica do XML pode **rebaixar um comprovante já em SUCESSO para PENDENTE_FOTO, apagar sua data de confirmação e recolocá-lo em PENDENTE_ENVIO**. A rotina pode então enviar novamente o comprovante, e o aceite recebe a data de hoje. O Dashboard conta esse estado datado e, como a data anterior foi sobrescrita, também perde confirmações em dias anteriores.

A diferença não decorre apenas de apresentação ou de comparar períodos diferentes. Há um defeito no fluxo de conciliação, além das diferenças de população e horário entre painel e planilhas. Não é correto interpretar 965 como 965 notas que ficaram digitalizadas pela primeira vez hoje.

## Evidência do defeito

Em `EtlRepescagemService.sincronizarInventarioSftpVedacit(String, VedacitSftpInventory)`, a condição de recuperação de XML sem data é avaliada antes da proteção de comprovante já concluído:

1. O registro SFTP tem `status_dados=SUCESSO`, mas não tem `data_processamento_dados`. O comprovante já pode estar em `SUCESSO`, com data própria.
2. A busca encontra um registro antigo com XML confirmado e datado para o mesmo par NF-e/CT-e. O comprovante desse registro antigo pode estar em `NAO_APLICAVEL` ou `PENDENTE_FOTO`.
3. `reconciliarRegistroSftpComLegado` usa o comprovante **do registro antigo** para definir a situação atual. Copia sua data nula e atribui `PENDENTE_FOTO` / `PENDENTE_ENVIO`, apesar do sucesso existente no registro SFTP.
4. A proteção contra reenvio procura um sucesso que acabou de ser sobrescrito. O documento volta à seleção, recebe outro aceite SOAP e passa a contar na data nova.

A reprodução chamou o método de inventário da classe **extraída do JAR operacional**, com repositório mockado, sem inicializar Spring ou conectar à rede. Os dois cenários produziram:

```text
LEGACY=NAO_APLICAVEL BEFORE=SUCESSO@2026-08-20T12:00 AFTER=PENDENTE_FOTO@null QUEUE=PENDENTE_ENVIO REPRODUCED=true
LEGACY=PENDENTE_FOTO BEFORE=SUCESSO@2026-08-20T12:00 AFTER=PENDENTE_FOTO@null QUEUE=PENDENTE_ENVIO REPRODUCED=true
```

JAR conferido: `4FD9D952C4982106B3CB27A717833F117FC5B095B7336E9D47307E22C7BE4A99`. A classe usada na reprodução coincide com a do JAR, SHA-256 `F2C431B9C6799DAEBEEFF74EA9A14C4E9B7A6AB48A4C487B151224AF6334C06D`.

O Git associa a ampliação da condição para XML com `SUCESSO` sem data ao commit `671a2e7` de 11/09. O método que substitui o estado do comprovante já existia. A reprodução confirma a combinação defeituosa no pacote publicado; o horário do commit não foi usado como horário da implantação.

Há ainda duas testemunhas reais da condição de entrada, consultadas somente em leitura: logs SFTP **9908 e 9910**, comprovantes confirmados em **07/09 às 20:43:46 e 20:44:20**, XML sem data, com legados **8762 e 8313** que possuem XML datado e comprovante `NAO_APLICAVEL`. Isso demonstra que o cenário reproduzido existe na auditoria real. A seleção futura depende também do inventário; não se afirma que esses dois serão necessariamente processados no próximo turno.

## O histórico anterior também mudou

Comparação do mesmo endpoint por etapa com a evidência preservada às 15:39–15:46 (`target/verificacao-painel-20260911/indicadores-setembro.json`) e a consulta atual:

| Dia do comprovante | Antes | Depois | Redução |
|---|---:|---:|---:|
| 07/09 | 169 | 51 | 118 |
| 08/09 | 84 | 24 | 60 |
| 09/09 | 10 | 8 | 2 |
| 10/09 | 50 | 47 | 3 |
| **Total** | **313** | **130** | **183** |

Esses 183 resultados históricos deixaram de aparecer nos dias anteriores. O contrato conta a primeira confirmação **ainda disponível** entre registros ativos; ele não consegue recuperar uma data apagada do próprio registro. Alterar apenas o gráfico não restaura esse histórico.

## Decomposição do número 965

A primeira consulta desta investigação já retornava 967. Às 20:46:43, o SQL encontrou 969. O valor 965 foi reconstruído ordenando as confirmações disponíveis do dia e selecionando as primeiras 965; a última delas está datada em **20:37:49**. Isso não estabelece o horário original das imagens, que não foi informado.

| Participação nas planilhas | Comprovantes contados nos 965 |
|---|---:|
| Notas fora das duas listas de pendências | 708 |
| Notas que saíram das 798 anteriores | 246 |
| Notas que ainda constam nas 505 atuais | 11 |
| **Total** | **965** |

Dos **708 fora das listas**, **701** têm tentativa anterior e um legado do mesmo par com XML datado, mas comprovante sem sucesso: **605 `NAO_APLICAVEL` e 96 `PENDENTE_FOTO`**. Seus registros atuais carregam a data antiga do XML e a nova data do comprovante. Esses 701 delimitam o grupo fortemente associado ao caminho defeituoso; não há uma fotografia anterior individual completa que permita recuperar a data original de cada um deles. Os outros sete têm uma tentativa registrada, seis de madrugada e um às 19:02:41. Portanto, subtrair 701 resulta em 264 demais confirmações, **não em uma certificação de 264 novas digitalizações no cliente**.

Exemplos de pares afetados, sem reproduzir chaves fiscais no relatório: ativo **8981** / legado **6144**, ativo **9411** / legado **7669**, ativo **9012** / legado **6033**. Os legados possuem XML de agosto e comprovante `NAO_APLICAVEL`; os ativos receberam aceites hoje, com duas, duas e quatro tentativas acumuladas, respectivamente.

Os 708 pares correspondem a **705 NF-es**. Essa diferença de três decorre da unidade NF-e/CT-e efetivo e é pequena frente ao excedente principal. Não há contribuição de PPG ou SELIA no número diário verificado.

Foram cruzados **738 de 738 pares confirmados após 17h**, até o corte das 20:46:43, com mensagens específicas de aceite SOAP nos logs; **702** estão fora das listas. A mensagem de aceite é emitida após `retorno.isStatus()==true`; a conciliação por duplicidade usa outro caminho. Portanto, houve chamadas de envio com aceite, e não apenas incremento visual do contador. O log não comprova quantas novas digitalizações o sistema do gestor passou a mostrar.

## O que as imagens e as planilhas comprovam

As **505 chaves atuais estão integralmente nas 798 anteriores**. Não houve entrada de nota nova nesse grupo: saíram exatamente **293**.

| Situação das 293 que saíram | Quantidade |
|---|---:|
| Confirmação local em 10/09, depois das 23:28 | 45 |
| Confirmação local em 11/09 | 246 |
| Saíram da planilha, mas continuam com timeout sem aceite local | 2 |
| **Total** | **293** |

As duas divergências são **NF 240679 / log 10009** e **NF 240898 / log 9994**. A saída da lista é evidência do relatório do gestor, mas não autoriza inventar uma confirmação local ou reenviar. Elas precisam de conciliação documental.

Logo, `798 - 505` mede a redução da pendência entre duas fotografias. Inclui 45 confirmações de ontem à noite e duas notas com retorno local ambíguo. Não equivale ao total integrado desde a meia-noite de hoje.

As imagens também mostram **Digitalizado: 5.606 → 5.917**, aumento de **311**. Os 293 pertencem apenas ao subgrupo `Pendente / ESL Sim`. As outras diferenças líquidas são dez em `ESL Não` e oito em `Não encontrado`; sem as 7.113 linhas completas das duas fotografias, não se pode reconstruir cada transição individual entre categorias.

## Situação das 505 notas no corte das 20:46:43

Classificação por NF-e, dando precedência à confirmação e mantendo uma linha por nota:

| Auditoria atual | NF-es |
|---|---:|
| Confirmação local | 16 |
| Pendente de envio | 263 |
| Bloqueio de origem | 123 |
| Timeout ambíguo | 2 |
| Sem auditoria ativa | 101 |
| **Total** | **505** |

Das 16 confirmadas, 15 possuem data de hoje entre 20:09 e 20:46 e uma tem confirmação de 18/08. Sem o horário da planilha atual, as 15 não devem ser tratadas automaticamente como falhas de atualização do cliente. Das 101 sem auditoria ativa, 67 não têm qualquer auditoria local; a diferença possui somente histórico arquivado. A ausência de auditoria não prova ausência de comprovante na ESL.

Arquivo detalhado: `target/divergencia-comprovantes-20260911/505-notas-conferidas.csv`. A análise usa a planilha anterior preservada em `target/auditoria-envios-20260910/falta_comprovante_vedacit_auditado.xlsx` e o anexo atual do usuário. Os arquivos originais foram preservados.

## Correção implementada e instalada

O usuário autorizou a correção rigorosa e parou o worker e a API. O banco recebeu V24/V25 às 21:33 BRT; o JAR e o frontend foram instalados após os testes e a confirmação da parada. Nenhum processo operacional foi iniciado pela correção. O início do relatório descreve a investigação anterior; esta seção registra o estado posterior.

- A recuperação de XML preserva o comprovante atual: sucesso, data, NF-e/CT-e efetivo, origem e metadados. Sucessos sem data também bloqueiam reenvio. Confirmação de outro CT-e efetivo não se transfere ao documento atual; timeout e recusa continuam retidos.
- Inventário e envio usam o mesmo bloqueio SQL por NF-e/CT-e, independentemente do perfil. O estado é relido dentro do bloqueio; chamadas aninhadas do mesmo documento reutilizam a proteção. A emissão XML mantém também sua exclusão por CT-e.
- `tb_confirmacao_comprovante` preserva o aceite fora do estado mutável da fila, inclusive após arquivamento. O trigger grava o aceite na mesma transação do log e rejeita rebaixamento de sucesso, mudança de data ou de identidade. O envio consulta esse registro antes de chamar o destino.
- Antes de iniciar o efeito externo, a aplicação persiste `EM_PROCESSAMENTO`/`TIMEOUT_AMBIGUO`. Se o processo cair antes de gravar o resultado, a mesma NF-e/CT-e permanece retida, inclusive em outra fila. A confirmação precisa ser conferida no destino. Isso evita usar a ausência de resposta como autorização de reenvio; não atribui garantia de transação única ao serviço remoto.
- V25 contém um manifesto congelado de **702 IDs e timestamps**, confere a assinatura encontrada e conserva os valores anteriores em `tb_ajuste_confirmacao_comprovante`. O ajuste da primeira data e seu registro são transacionais. O log bruto não é reescrito e não recebe datas estimadas. A contagem 701 se referia ao subconjunto fora das planilhas dentro dos primeiros 965; o manifesto final abrange a assinatura completa do incidente.

## Indicador corrigido

No corte estável de 21:33 BRT, os **976** retornos datados hoje foram qualificados como:

| Tratamento no indicador | Quantidade |
|---|---:|
| Confirmações com primeira data utilizável em 11/09 | 274 |
| Aceites com primeira data original sob conferência | 702 |
| Total bruto preservado | 976 |

Os 702 têm confirmação, mas a auditoria disponível não demonstra, individualmente, quando ocorreu o primeiro aceite. Portanto, **não são apresentados como 702 duplicidades comprovadas nem como novas integrações de hoje**. O novo campo `confirmadosSemDataConfiavel` os mostra separadamente, fora de períodos/séries e fora da fila de pendências de envio. A correção não consiste em subtrair um número fixo no frontend: o SQL qualifica cada par e o contrato v2 agrega somente datas utilizáveis.

De 01–11/09, o gráfico tem 404 confirmações datáveis; em 11/09, 274. Cards e séries usam a mesma agregação. Logs arquivados podem fornecer evidência de aceite para um par ativo. Outros destinos mantêm sua identidade por ocorrência. Falhas continuam significando o último resultado disponível, não o histórico completo de tentativas. A auditoria detalhada identifica a data bruta como **Último retorno comprovante**. A interface explica que sua base difere da redução de pendências na planilha do gestor.

Os 183 registros perdidos da distribuição anterior por dia não foram distribuídos por estimativa. Recuperar essas datas exige evidência individual adicional. As duas notas com timeout identificadas acima também não foram marcadas como sucesso sem prova.

## Validação e artefatos

- Suíte Java completa: **385 aprovados, zero falhas/erros**, em diretório isolado com rede bloqueada (`target/unit-tests/coverage-20260911-214640-065/`; cobertura de linhas 72,40%). Regressão repetida 4.000 vezes; 200 disputas entre perfis em 24 threads; quedas entre chamada e gravação e falha da auditoria antes do envio. Três testes manuais de imagem/rede externa permanecem explicitamente desabilitados.
- SQL Server real, limitado a SATELITE_TMS_AUDITORIA: 1.000 conciliações de XML, arquivamento, inserção múltipla, duplicidade, CT-e efetivo e datas incertas; corrupção de status, remoção/troca da data e troca de identidade recusadas pelo trigger. Registros sintéticos revertidos integralmente.
- Hibernate real com o usuário da aplicação confirmou inserção IDENTITY com trigger, confirmação atômica, atualização independente do XML e rollback sem resíduos de auditoria.
- Duas instâncias reais do serviço de lock, oito threads e 100 disputas no SQL Server: máximo de uma seção crítica simultânea, uma execução simulada e reentrada entre perfis. Nenhuma chamada SOAP/ESL feita pelos testes.
- Dez leituras reais estáveis: 274/702; resumos iguais à soma das séries; filtro de destino e validação de schema com o usuário da aplicação aprovados.
- Dashboard: 703 testes backend e 374 frontend aprovados; TypeScript, lint, encoding, validação do ambiente e builds. Oito cenários de navegador com dados agregados reais, APIs interceptadas e DNS externo bloqueado: 390/1265/1920 px, temas claro/escuro, filtro de destino e erro sem fallback. Não houve acesso de teste à conta do gestor.
- JAR final instalado às **21:50 BRT**, SHA-256 `2F66CD9CEABB1A11B0168EB53EBCD3F5C0C15DE60FE4EE0CFD4D77ECB59B8ECB`, com 1.253 entradas verificadas; comparado entrada a entrada com a compilação testada, sem dependência H2. Frontend publicado com build `20260911-confirmacoes-v2`, assets anteriores preservados e HTML ativado por último. HTTP 200 e metadados do frontend conferidos. API/worker permanecem parados até início pelo operador; o contrato HTTP v2 deve ser conferido após essa retomada.

Manifesto final e backups: `target/correcao-idempotencia-20260911/instalacao.json`, `satelite-antes.jar`, `backup-instalacao-*.jar` e `backup-ui/`. Scripts reproduzíveis: `scripts/testar_com_cobertura.ps1`, `scripts/executar_sql_auditoria.ps1`, `scripts/probes/*.java` e `scripts/instalar_correcao_comprovantes.ps1`. Atualização SQL pelo entrypoint `database/subir_database.bat --confirmacoes`. Evidências SQL, hashes e resultados completos ficam no diretório do incidente; imagens em `../etl-dash/dashboards/frontend/.tmp/confirmacoes-v2-visual/`.

## Evidências e limites

SQL reproduzível: `database/sql/diagnostico/20260911_divergencia_comprovantes.sql`, parametrizado com arrays JSON de chaves e janelas de data. Execução com transação encerrada por rollback, timeout de consulta e lock, sem escrita SQL. As consultas são sequenciais sob o isolamento normal do banco; o trabalhador continuou funcionando. Os cortes e a reconstrução de 965 evitam tratar números crescentes como se fossem uma única fotografia imutável.

Evidências locais ignoradas pelo Git em `target/divergencia-comprovantes-20260911/`: `consulta-1.csv` a `consulta-11.csv`, `resumo.json`, `api-setembro.json`, `historico-alterado.csv`, `aceites-soap-log.csv`, `verificacao-logs.json`, `InventoryRegressionProbe.java`, `regressao-reproduzida.log`, `classe-validada.json` e `505-notas-conferidas.csv`. Credenciais, imagens e XMLs fiscais não foram incluídos no relatório nem no SQL versionado.
