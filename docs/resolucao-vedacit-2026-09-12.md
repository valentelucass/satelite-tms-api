# Conferência e correções Vedacit — 12/09/2026

Continuação da [auditoria das capturas](auditoria-vedacit-2026-09-12.md), com investigação independente das configurações FileZilla, SFTP, origem ESL e consulta Vedacit. O usuário autorizou procurar as evidências e executar a investigação, sem depender de novos arquivos fornecidos por ele.

## Resultado aplicado

Às **17:58 BRT**, `database/subir_database.bat --saneamento-vedacit` aplicou exclusivamente a V26 em `SATELITE_TMS_AUDITORIA`:

| Correção | Antes | Depois |
|---|---:|---:|
| XMLs “a conferir” no painel | 503 | **465** |
| Comprovantes com classificação residual, apesar de aceite permanente | 5 | **0** |
| Comprovantes pendentes na seleção bruta | 3 | **1** |
| Bloqueios/timeouts na classificação bruta de comprovante | 146 | **143** |

Os 38 removidos da contagem XML são **linhas de auditoria de comprovantes com nome incompleto**, sem chave fiscal identificada na linha. Apenas `status_dados` passou de `SUCESSO` sem evidência para `NAO_APLICAVEL`; o bloqueio do comprovante continua. Não foram enviados 38 XMLs nem apagados arquivos/logs.

Os logs **9972, 9973, 9687, 9813 e 10260** receberam a classificação de comprovante já demonstrada pelo aceite permanente do par exato. As datas vêm do registro de aceite existente. Nenhuma primeira data foi inventada e nenhum comprovante foi retransmitido. O estado anterior dos 43 logs está preservado em `tb_ajuste_estado_vedacit`.

A comparação integral das respostas HTTP antes/depois comprovou que **a única mudança no indicador foi 503 → 465**. Permanecem 824 XMLs e 911 comprovantes datáveis em 01–12/09, 702 comprovantes com primeira data incerta, 79 XMLs bloqueados e 143 comprovantes bloqueados. Todas as séries diárias e demais campos coincidem.

## Conferência dos 465 CT-es

A configuração de conexão FileZilla coincide com o perfil SFTP Vedacit do projeto. A inspeção ficou nas pastas `xml` e `comprovantes` desse cliente, com fingerprint fixada e verificação de estabilidade dos arquivos.

- **80 CT-es:** XML presente, com a NF-e referenciada no conteúdo; comprovante do par presente.
- **385 CT-es:** XML ausente na pasta auditada; comprovante do par presente.
- Todos possuem marca histórica local `SUCESSO` XML sem data de confirmação. Arquivo disponível no SFTP não prova recebimento na Vedacit.

A [relação individual dos 465 CT-es](auditoria-vedacit-20260912-xml-conferidos.csv) registra ID de auditoria, número resumido do CT-e e presença dos arquivos. Não inclui chaves fiscais completas, imagens ou XMLs.

Foi executada **uma consulta SOAP `buscarCTePorChave`** usando o cliente gerado e o cabeçalho de autenticação do projeto. A Vedacit devolveu **autorização negada**; a execução interrompeu as demais consultas. Portanto, o recebimento desses 465 não pôde ser confirmado no destino. A seleção bruta inicial desse probe tinha 467 representantes, incluindo dois CT-es com outra linha ativa datada; a conferência SFTP e o CSV usam o conjunto correto de **465** do indicador. As linhas `NAO_CONSULTADO_APOS_BLOQUEIO` não são consultas remotas executadas.

O download de XML em `/api/ctes` também foi testado uma vez, com a credencial configurada para essa operação: **HTTP 401** às 17:42. Nenhuma credencial foi trocada, adivinhada ou divulgada. Não marcar os 465 como recebidos nem enviá-los novamente com base apenas na ausência de data.

## Os 38 comprovantes com nome incompleto

Todos os nomes têm uma referência seguida de separador vazio e chave de NF-e, como `<referencia>__<NFe>.jpg`. Falta a chave CT-e exigida na identificação do arquivo. Os arquivos existem e têm conteúdo; a rejeição não significa pasta vazia.

A chave NF-e extraída do nome permitiu consultar cada nota na ESL:

- 38 consultas do evento 110: HTTP 200, sem CT-e correlacionado retornado.
- 38 consultas do evento 1: HTTP 200, todas com ocorrência de entrega, **nenhuma com chave CT-e válida retornada**.
- Uma leitura adicional do contrato da primeira amostra confirmou `freight` presente e `freight.cte_key` explicitamente nulo. Não se trata de alteração do nome desse campo no DTO.
- Não há correlação local dessas NF-es com CT-e ou comprovante já aceito.

Assim, renomear os arquivos apenas com o número da NF-e não resolve a identificação. A origem precisa fornecer a chave ou uma relação documental verificável. A ocorrência de entrega sozinha não permite escolher um CT-e por aproximação.

## Comprovante pendente e problemas adicionais

O pendente continua sendo **log 7579 / NF 233409 / CT-e 53102**. O SFTP contém arquivo da mesma nota para CT-e **52321**, já aceito; não há arquivo do par requerido. A consulta específica `/api/freight_delivery_receipts?cte_key=...` foi executada com a prioridade de credenciais do serviço e retornou **401**, às 18:06. O modo exclusivo SFTP não foi alterado.

A investigação confirmou **38 linhas de erro arquivadas referentes a 26 CT-es sem sucesso histórico**, retiradas da fila em 11/09. Incluem 10561–10566 e não têm linhas ativas do mesmo CT-e. A V21 foi corrigida para provisionar a estrutura de arquivamento sem repetir o `UPDATE` abrangente de todo Vedacit sem cliente SFTP. **Essas linhas históricas ainda não foram reativadas**: precisam de manifesto e correlação documental próprios, preservando estados de envio. O CSV de evidência está em `target/resolucao-vedacit-20260912/arquivados-recentes.json`.

Continuam separados: 25 timeouts de comprovante sem aceite local exato, uma recusa Vedacit (9420), ciclo antigo 986 sem registro de interrupção, 38 CT-es SFTP sem sucesso histórico (37 complementares/um substituto, 12 sem auditoria) e 20 arquivos XML fora do contrato CT-e reconhecido. Não foram declarados resolvidos por ajuste de contagem.

## Código e pacote

- `LogIntegracaoRepository` e `EtlRegistroService`: a recuperação alcança também inventário Vedacit em `PENDENTE_ORIGEM`, nunca tentado, sem data/mensagem, além das falhas de obtenção já cobertas. Mantém exclusão por documento, releitura do estado, espera entre tentativas e retenção de sucesso histórico/resultado ambíguo. SQL real selecionou exatamente **79** registros e respeitou o limite de dez.
- `EtlRepescagemService`: novas rejeições de arquivo sem ambas as identidades fiscais recebem XML `NAO_APLICAVEL`; rejeição de um arquivo já identificado continua preservando sua etapa XML.
- V21: removido arquivamento repetido por ausência de cliente SFTP. Nenhum registro histórico reativado ou excluído por essa alteração.
- V26: saneamento aplicado e histórico anterior preservado. O novo modo `--saneamento-vedacit` executa somente essa migration, sem reaplicar as anteriores.

**Candidato pronto, ainda não instalado:** `target/resolucao-vedacit-20260912/satelite-0.0.1-SNAPSHOT.jar`, SHA-256 `8F5E86BF6820E6D7DF4126FCF794D55186960FB0E8A858BB1C434EAEDCB81926`.

O JAR operacional permanece `2F66CD9CEABB1A11B0168EB53EBCD3F5C0C15DE60FE4EE0CFD4D77ECB59B8ECB`. A correção SQL já aparece na API; a nova seleção dos 79 exige instalação e retomada do pacote. O `CONTEXTO_GLOBAL.md`, seção 5, reserva o controle do runtime ao humano. Nenhum processo foi parado/iniciado para esta entrega.

## Validação e evidências

- Suíte final: **392 testes, 389 aprovados, zero falhas/erros e três manuais desabilitados**; bloqueio de rede ativo na JVM de testes. A primeira rodada encontrou duas falhas no texto sintético do novo teste de rejeição; a mensagem foi ajustada ao contrato real e a suíte final passou integralmente.
- V26 ensaiada duas vezes na mesma transação com rollback: 43 registros no manifesto, segunda aplicação idêntica, 9.003 logs preservados; nenhum aceite permanente, identidade, arquivamento, tentativa ou data XML alterado. Aplicação efetiva pelo atualizador restrito concluída depois desse ensaio.
- Consulta real do repositório contra SQL Server: 40 SELECTs, máximo de 502 parâmetros, rollback; 79 inventários XML recuperáveis, saldo bruto de um comprovante e nenhuma seleção técnica; ordenação, limite e paridade com SQL independente aprovados.
- JAR: 1.253 entradas de aplicação idênticas às classes/recursos testados, launcher presente e nenhuma dependência H2 incluída. Empacotamento Java 17 offline aprovado.
- Nesta continuação: um SOAP de consulta, 79 GETs ESL (77 HTTP 200 e dois 401); **zero envio documental** e zero escrita remota SFTP. As únicas escritas SQL de produção foram as correções técnicas versionadas da V26.
- Evidências: `target/resolucao-vedacit-20260912/`, especialmente `comparacao-indicadores.json`, `validacao-v26.json`, `aplicacao-v26.log`, `conferencia-sftp.json`, `consulta-destino.json`, `consulta-origem.json`, `rejeitados-origem.json`, `rejeitados-entrega.json`, `rejeitado-contrato.json`, `pod-pendente-origem.json`, `sql-query.json`, `testes-finais.json` e `pacote.json`.
