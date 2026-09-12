# Auditoria Vedacit — 12/09/2026

**Registro da auditoria inicial.** A [continuação com conferência e correções aplicadas](resolucao-vedacit-2026-09-12.md) reduziu XML “a conferir” de 503 para 465 e conciliou cinco classificações de comprovante às 17:58. Os números e a declaração de somente leitura abaixo descrevem o corte inicial.

Auditoria realizada entre 17:16 e 17:31 BRT. Escopo: reproduzir as duas capturas, identificar as pendências e explicar a ausência de novos envios. Leituras da API local, SQL exclusivamente em `SATELITE_TMS_AUDITORIA`, logs, PM2 e SFTP Vedacit. Nenhum envio SOAP, chamada ESL adicional, alteração de banco, credencial, configuração, JAR ou controle de processos operacionais.

## Conferência das capturas

As 18 contagens de pendências da primeira captura coincidem integralmente com a API. A decomposição independente em SQL confirmou os grupos Vedacit. No período 01–12/09, a API retorna os mesmos **824 XMLs/dados e 911 comprovantes** da segunda captura. São confirmações datáveis no escopo do indicador; não representam todos os envios históricos nem todas as tentativas.

| Vedacit | Pendentes | Bloqueados | A conferir | Composição confirmada |
|---|---:|---:|---:|---|
| XML/dados | 0 | 79 | 503 | 79 CT-es sem XML confirmado; 465 CT-es com `SUCESSO` sem data + 38 registros sem chave CT-e |
| Comprovante | 1 | 143 | 0 | 79 dependências XML + 38 nomes/chaves inválidos + 25 timeouts ambíguos + 1 recusa de destino |

Os 79 pares e os 38 arquivos aparecem nas duas etapas. **222 etapas bloqueadas não significam 222 notas distintas.** O saldo considera todas as datas. Há ainda **702 comprovantes confirmados com primeira data incerta**, campo separado do contrato v2, fora de pendências e da série datada; não confundir com os 503 XMLs a conferir.

## O comprovante pendente

- **Log 7579, NF 233409, CT-e 53102**, ocorrência 300010016.
- XML confirmado em 08/08 às 02:02:21.368; comprovante `PENDENTE_FOTO`/`PENDENTE_ENVIO`, zero tentativa de envio registrada.
- A leitura atual do SFTP encontrou comprovante da NF para **CT-e 52321**, já confirmado nos logs 7304 (arquivado) e 8882 (ativo), em 08/08 às 22:01:46.833. Não encontrou arquivo para o CT-e 53102.
- O worker registra “Canhoto não encontrado no SFTP para o lote exclusivo; fallback ESL desabilitado”. Continua tentando localizar o par, sem transmitir uma imagem inexistente.
- A resolução exige documento do CT-e correto ou prova documental do relacionamento entre os CT-es. Sucesso em outra combinação da mesma NF não autoriza trocar a chave nem reenviar.

O saldo bruto do worker mostra **3 candidatos**, porque inclui também logs **9972/NF 238648 e 9973/NF 238367**. Ambos já têm confirmação permanente do par, respectivamente em 07/09 às 20:43:46.443 e 20:44:20.697. A proteção impede o reenvio e o painel conta apenas uma pendência, mas esses dois logs ainda conservam classificação residual `PENDENTE_ENVIO`. Isso explica ciclos com três selecionados, uma pendência e zero enviados.

## Os 79 XMLs bloqueados e os 503 a conferir

**79 bloqueados:** todos possuem `PENDENTE_ORIGEM`, zero tentativas XML, data da etapa nula e comprovante no SFTP. Nenhum dos 79 tem XML correlacionado disponível no SFTP auditado ou sucesso XML histórico local, incluindo arquivados. Falta documento de origem e confirmação de integração; não são 79 recusas SOAP.

Existe uma lacuna operacional: `LogIntegracaoRepository.findXmlFalhaOrigemParaRecuperacao` exige data de tentativa anterior e mensagem `ORIGEM_XML_*` ou assinaturas equivalentes. Esses registros criados pelo inventário não satisfazem a seleção. A consulta real encontrou **zero elegíveis**. O incremental no fim da fila não recupera automaticamente todo esse estoque antigo.

**503 a conferir:** todos os representantes estão como `SUCESSO`, sem data XML e sem tentativa XML registrada. A composição é:

- **465 CT-es identificados:** 463 têm comprovante confirmado e dois têm timeout de comprovante. Não foi encontrada confirmação XML datada, mesmo no histórico arquivado. O aceite do comprovante não comprova quando ou como o XML foi integrado.
- **38 registros técnicos sem chave CT-e/NF-e:** são os arquivos rejeitados pelo nome/chaves, mantendo marcação histórica de dados `SUCESSO` sem data. O indicador usa identidade `LOG:id`, portanto não são 38 XMLs fiscais identificados. A correção de código evita criar essa marcação em novas rejeições, mas preservou os registros antigos.

Não preencher datas nem reenviar esses 465 CT-es por suposição. Os 38 registros técnicos precisam de tratamento explícito na semântica do indicador e de eventual saneamento lógico versionado, preservando a auditoria.

## Os 143 comprovantes bloqueados

| Motivo | Quantidade | Situação |
|---|---:|---|
| Dependência XML | 79 | POD presente no SFTP, XML correlacionado ausente, sem confirmação histórica |
| Nome/chaves inválidos | 38 | Rejeição documental no inventário; não há identidade segura para envio |
| Timeout ambíguo | 25 | Sem sucesso local no mesmo par, inclusive arquivados; requer conferência com destino |
| Recusa Vedacit | 1 | Log **9420**, NF **28575**, CT-e **51884**: “Não foi localizado um canhoto compativel com os dados informados.” |

Os timeouts passaram de 23, no corte de ontem às 17h, para 25. Os dois adicionais são **9994/NF 240898**, às 19:16:06, e **9812/NF 240858**, às 20:06:42 de 11/09. Nenhum novo timeout está datado em 12/09. A relação integral por ID/NF/CT-e resumido está no CSV da auditoria.

O worker ainda conta **121 bloqueios de origem/destino + 25 timeouts = 146 registros**. O painel retorna 143 porque os logs **9687, 9813 e 10260** já têm confirmação permanente de comprovante. Suas classificações brutas continuam bloqueadas. Os três também têm XML correlacionado no SFTP e sucesso XML em outra linha; são resíduos de conciliação, distintos dos 79 efetivamente bloqueados no indicador.

## Por que não houve XML novo hoje

O XML **executou**. Até 16:50, foram **19 ciclos completos iniciados hoje**, todos com uma ocorrência avaliada, uma já processada, zero enviada e zero erro. A telemetria registra 19 `OCCURRENCE_LIST` HTTP 200. O ciclo seguinte iniciou às 17:21 e repetiu a mesma resposta, elevando a 20 as varreduras observadas nos logs.

Todas consultam o evento **110**, com `start=311117182`, sem `since` nem NF específica. A origem devolve a última ocorrência já processada e repete o cursor. O fluxo encerra como fim de fila e o PM2 agenda nova passagem 30 minutos após o término. As flags XML/SFTP/envio CT-e estão habilitadas. O último aceite XML encontrado em todo o histórico é **11/09 às 20:29:30.636**; o último entre os logs ativos é 20:17:43.340.

Isso comprova ausência de novidade na consulta incremental observada. Não comprova inexistência de XML antigo recuperável, nem regularização da autorização do download ESL. Nenhum download ESL de diagnóstico foi realizado nesta auditoria.

**Pendência crítica descoberta:** os seis logs de HTTP 401/espera de autenticação **10561–10566 foram arquivados em 11/09 às 17:29:47.605**, ainda sem sucesso XML histórico. Ocorreram novos arquivamentos às 18:34:07.251 e 21:59:54.673, todos com motivo `LEGADO_SEM_CLIENTE_SFTP`. Os três grupos de 11/09 somam 351 linhas, incluindo **39 linhas de erro / 27 CT-es**, dos quais **26 não têm sucesso XML histórico**. Esses erros saíram do indicador ativo e da seleção de recuperação; a falta de falha no gráfico não comprova resolução.

A V21 tem atualização abrangente de todo Vedacit sem `sftp_cliente` ainda ativo. O procedimento geral `subir_database.bat` percorre novamente as migrations; essa regra pode atingir logs XML recentes, pois ausência de cliente SFTP não prova antiguidade. A assinatura e os horários são compatíveis com essa regra, mas a auditoria não identificou quem ou qual execução provocou cada arquivamento. Não executar o atualizador geral como tentativa de destravar a fila antes de delimitar essa regra.

## Por que os comprovantes deixaram de subir

O ciclo **987**, iniciado em 11/09 às 22:04:37.382, concluiu naturalmente em **12/09 às 05:25:05.181**. Registrou 510 avaliações, **507 envios**, uma pendência, duas avaliações sem novo envio e zero erro. Os turnos do log somam **149 envios em 11/09 + 358 em 12/09**, coincidindo com a evolução após a retomada. O último aceite ocorreu às **05:24:56.706**.

Desde então, os ciclos concluídos repetem três candidatos brutos, uma pendência real e zero envio. A passagem envia até esgotar os elegíveis; o término observado não decorre do teto de dez por turno. Os bloqueios e o arquivo do par ausente explicam o saldo restante. Há trabalho de inventário de aproximadamente cinco a seis minutos por ciclo, mesmo sem envio, por revisão em partes e consultas individuais; isso é uma pendência de desempenho, sem evidência de travamento neste recorte.

Permanece também a linha antiga **986**, iniciada em 11/09 às 20:32, `EM_EXECUCAO`, sem término, com última atualização às 20:58:54.324. Ela pertence ao processo anterior interrompido e não representa o ciclo atual. Falta registrar interrupção por regra operacional própria, sem inventar encerramento bem-sucedido.

## Conferência independente do SFTP e da proteção

Leitura entre 17:22:44 e 17:24:49, com fingerprint configurada, caminhos restritos, validação de estabilidade e bytes XML somente em memória:

- 3.246 comprovantes válidos, 3.157 NF-es distintas e 38 nomes rejeitados, coincidentes com o inventário do worker.
- 1.503 arquivos `.xml`: 1.483 reconhecidos como CT-e e 20 sem `infCte` único no namespace exigido. Não equiparar esses 20 a XML malformado sem análise de seu contrato específico.
- 38 CT-es reconhecidos no SFTP sem status histórico XML de sucesso: 37 complementares (`tpCTe=1`) e um substituto (`tpCTe=3`); 12 não têm auditoria. Nenhum possui par com comprovante no inventário. É uma frente de recuperação documental separada, condicionada à ocorrência/correlação e à conferência do destino.
- Zero arquivo alterado durante leitura e zero falha de leitura. Zero SOAP, ESL adicional, escrita SQL ou remota.
- JAR operacional conserva SHA-256 `2F66CD9CEABB1A11B0168EB53EBCD3F5C0C15DE60FE4EE0CFD4D77ECB59B8ECB`. API PID 47276 ativa; novo ciclo PM2 observado com PID 51880 e intervalo de 1.800.000 ms, sem ação de controle pela auditoria.
- Trigger de preservação ativo. Zero log Vedacit de sucesso com par válido ausente da confirmação permanente. Há 2.556 sucessos legados arquivados sem identidade completa, fora do contrato desse registro permanente; não os tratar como falha nova da proteção.

## Evidências e encaminhamento

**32 verificações aprovadas:** 18 números da captura, oito classes de saldo Vedacit contra SQL, duas confirmações do período, duas igualdades card/série e os dois totais de hoje. Scripts SQL executados com rollback; não houve alteração de aplicação, portanto não se executou suíte de envio ou processo Spring adicional.

- SQL reproduzível: `database/sql/diagnostico/20260912_auditoria_vedacit.sql` e `20260912_residuais_vedacit.sql`; usar `scripts/executar_sql_auditoria.ps1 -Arquivos <arquivo> -Rollback`.
- Evidências locais: `target/auditoria-vedacit-20260912/`, com respostas HTTP, SQL, SFTP, runtime sanitizado, turnos e validações.
- `pendencias-por-log.csv`: **726 linhas de etapa**, sendo 79 + 503 de XML e 1 + 143 de comprovante; chaves resumidas e IDs para rastreabilidade, sem payloads ou imagens. Há sobreposição entre etapas.
- `xml-bloqueados-sftp.csv`: cruzamento individual dos 79 bloqueios XML com o inventário remoto; 79 comprovantes presentes e zero XML correlacionado.

Próximos trabalhos registrados no `STATES.md`: corrigir o alcance do arquivamento e a seleção de recuperação; obter XML dos 79 pares; conciliar os 465 sucessos XML sem data e os 38 registros técnicos; conferir os 25 timeouts e a recusa; tratar a NF 233409 pelo CT-e correto; conciliar as cinco classificações residuais e o ciclo interrompido. Esta entrega conclui a auditoria e não aplica reenvios ou baixas operacionais.
