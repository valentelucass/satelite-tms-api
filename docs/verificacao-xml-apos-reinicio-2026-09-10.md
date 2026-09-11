# Verificação após o reinício — 10/09/2026, 21:27–21:30 BRT

## Comprovantes com aceite real — 23:30 BRT

O acompanhamento alcançou a etapa de comprovantes. A varredura XML retornou ao runner às **23:27:52**, após 18 páginas e 360 ocorrências, com **304 CT-es confirmados** no banco e nos indicadores. A última página teve oito resultados de erro; seis chamadas de download XML pela ESL registraram explicitamente **HTTP 401 Unauthorized**. Esse caminho é usado quando a busca SFTP não entrega XML válido e usa a credencial `RODOGARCIA_MASTER_API_REST`. O erro confirma recusa de autenticação/autorização nesse acesso, sem determinar se a causa é expiração, valor ou escopo da credencial. O cursor da página com erro foi preservado para retomada; a fila XML não foi esgotada.

Depois, a sincronização promoveu os comprovantes com XML aceito: o indicador passou de um para 280 pendentes, e os bloqueios de comprovante caíram de 836 para 557. A consulta SQL deixou de encontrar XMLs novos ainda com a classificação antiga de bloqueio. O primeiro candidato continuou sem arquivo do CT-e exato, mas isso não interrompeu a passagem.

Foram conferidos **três aceites positivos SOAP de comprovantes**, às **23:28:26**, **23:29:06** e **23:29:38**. Às 23:29:55, o endpoint já mostrava **270 comprovantes confirmados no período**, contra 267 antes do ciclo, e 277 pendentes. As chamadas SOAP desses três envios levaram aproximadamente 2,5, 36,6 e 28,2 segundos, além do download/preparo e da pausa entre itens. Há, portanto, espera real por resposta do destino, além da espera anterior pela etapa XML. Os detalhes de cada comprovante ficam no log `logs/2026-09-10/etl-execucao-23-13-09.log`, não no resumo de console do PM2.

**Situação na última conferência:** XML e comprovantes tiveram envios reais, e o dreno de comprovantes continuava em execução. O atraso inicial ocorreu porque os comprovantes aguardaram cerca de 15 minutos pela varredura XML. Permanecem a melhoria da alternância, a recusa 401 no download XML da ESL e a conferência do saldo residual. A amostra de três comprovantes não comprova conclusão de todos os pendentes nem recuperação integral das planilhas. Nenhum envio, reinício ou alteração de credencial foi iniciado pela inspeção.

Evidências adicionais: `sql-apos-transicao.txt` e `indicadores-apos-transicao.json` em `target/verificacao-comprovantes-20260910-2319/`. A seção seguinte preserva a fotografia anterior à transição.

## Comprovantes aguardando a varredura XML — 23:26 BRT

A conferência adicional confirmou um atraso provocado pela ordem das etapas. `WorkSftpClientesRunner.executarCiclo()` chama `orquestrador.executarXmlVedacit()` de forma síncrona antes de listar, sincronizar e processar o inventário de comprovantes. `EtlFluxoDestinoService` continua a paginação até o fim da origem; `maxPaginas=10` determina uma pausa de 30 segundos, não o retorno ao runner. Em produção, a página 10 terminou às 23:22:22, a 11 começou às 23:22:52 e a execução chegou à página 16 às 23:25:49 sem resumo final XML. Assim, mesmo comprovantes cujo XML acabou de ser confirmado precisam esperar o restante da varredura.

Uma consulta exclusivamente de leitura em `SATELITE_TMS_AUDITORIA`, às **23:26:14**, encontrou:

- **275 CT-es com XML confirmado desde o start das 23:13:06; zero comprovantes com nova confirmação nesse intervalo.** A última confirmação de comprovante na auditoria ativa continuava às 17:24:50.
- **252 pares NF-e/CT-e distintos do cliente SFTP** com `status_dados=SUCESSO`, data XML posterior ao start, referência de comprovante preenchida, `status_canhoto=PENDENTE_FOTO` e classificação ainda `BLOQUEADO_ORIGEM`. O bloqueio de falta de XML ficou desatualizado; a promoção para `PENDENTE_ENVIO` está implementada em `promoverCandidatoSftpComDadosConfirmados`, mas só é chamada na sincronização do inventário, depois do fim da varredura XML. A existência atual e a validade do arquivo serão conferidas nessa etapa; a consulta não significa aceite do comprovante pela Vedacit.
- Três outros pares bloqueados já têm sucesso ativo de comprovante e continuam aguardando a conciliação, sem necessidade de reenvio.
- Um registro permanece na fila `PENDENTE_ENVIO`; 19 resultados ambíguos e uma recusa de destino permanecem retidos. Os 38 registros SFTP rejeitados não devem ser confundidos com documentos prontos para envio.

Às 23:26:21, o worker ainda estava online no mesmo PID 28988, com 279 aceites XML no log e nenhuma linha WARN/ERROR desde o start. A consulta HTTP, capturada alguns segundos antes, mostrava 274 XMLs e 267 comprovantes no período 01–10/09. As capturas de log, SQL e HTTP são sequenciais durante uma execução ativa, portanto seus totais XML não representam o mesmo instante. O total de comprovantes permaneceu inalterado.

**Conclusão:** a etapa de comprovantes ainda não foi alcançada neste ciclo; não há evidência de nova recusa SOAP de comprovantes nesta execução. A recuperação XML está avançando, mas a ordenação atual faz o acúmulo XML atrasar o envio dos comprovantes já liberáveis. A melhoria necessária é intercalar passagens de XML e comprovantes, respeitando XML confirmado antes do comprovante do mesmo par, pausas, locks e retenções. O limite por lote não deve virar teto diário nem exigir esgotar todos os XMLs antes de dar vez aos comprovantes. A duração restante da origem não foi determinada nesta inspeção.

Nesta verificação não houve mudança de código de aplicação, configuração, cursor, SQL ou runtime. Evidências agregadas e sanitizadas em `target/verificacao-comprovantes-20260910-2319/`, incluindo a consulta JDBC somente leitura e os snapshots dos indicadores. A verificação do gargalo está concluída; a alteração da alternância continua pendente. O aceite real de comprovantes foi confirmado no acompanhamento posterior descrito acima.

## Primeiro processamento confirmado — 23:17 BRT

O usuário iniciou o worker às 23:13:06. O processo ID 189/PID 28988 carregou as flags novas e iniciou a etapa XML/evento 110 às 23:13:27, a partir do cursor técnico existente. A primeira confirmação SOAP positiva foi registrada às 23:15:14.

Às **23:17:25**, a conferência encontrou **37 aceites XML de CT-es distintos no log e os mesmos 37 confirmados no endpoint de indicadores**, que anteriormente retornava zero em setembro. Foram concluídas duas páginas e o cursor avançou; o worker permanecia online, sem nenhuma linha ERROR no ciclo observado. Portanto, já ultrapassou dez envios na mesma execução. O total de comprovantes permanecia em 267: a etapa XML ainda não tinha terminado e o processamento de comprovantes ocorre depois dela. Esta é uma confirmação parcial de operação; não significa que toda a fila ou as planilhas foram recuperadas.

A obtenção inicial dos XMLs levou tempo: a leitura de threads mostrou busca/listagem de arquivos no SFTP. Depois surgiram os aceites; não houve evidência de travamento nessa amostra. A inspeção foi somente leitura, sem reiniciar/interromper o worker ou gerar requisições adicionais de envio. Evidências sanitizadas e JSONs do endpoint em `target/primeiro-ciclo-xml-20260910-2314/resultado-parcial.json` e arquivos adjacentes.

Foi identificada uma limitação adicional de telemetria: o resumo genérico da primeira página informou `enviadas=2`, enquanto houve 17 aceites XML distintos. A conversão de resultado considera o canhoto `PENDENTE_FOTO` mesmo após XML bem-sucedido, reduzindo o contador geral. O endpoint por etapa usa `status_dados`/data próprios e contou corretamente. O ajuste desse resumo foi registrado como pendência, sem alterar status ou código durante os envios.

## Atualização — configuração aplicada às 21:36 BRT

Após o usuário solicitar a aplicação e reservar o start para si, as seis opções preparadas no ecosystem foram aplicadas ao cadastro efetivo do worker e à sua entrada persistida em `dump.pm2`: XML, fonte SFTP XML, envio de CT-e e dreno habilitados; três erros consecutivos interrompem a passagem, com 1.000 ms entre tentativas XML. A configuração `.env` compartilhada foi preservada; as opções específicas do worker têm precedência sobre ela.

O cadastro foi recriado **parado**, sem executar o Java, usando o ramo correspondente de `prepare` do PM2 7.0.1. Esse ramo foi verificado antes com uma simulação que reprovaria qualquer tentativa de execução. O nome `WORK-SFTP-CLIENTES` permanece; o ID passou de **28 para 189**. Conferência final: `stopped`, PID 0, `autostart=true` para o futuro início humano e todas as flags presentes tanto no ambiente efetivo quanto no persistido. Nenhum outro processo foi alterado; logs, argumentos e JAR foram preservados. A API continua no PID 37668 e respondeu HTTP 200/versão 1. Backups privados e evidências em `target/ativacao-worker-xml-20260910/`.

O usuário iniciou o worker às 23:13 e os primeiros aceites foram confirmados conforme as seções acima. Não houve XML enviado durante a aplicação das configurações às 21:36. O diagnóstico abaixo descreve o estado anterior à alteração.

O novo pacote está em uso e a rota de indicadores respondeu HTTP 200, contrato versão 1. Os valores coincidem com a captura fornecida pelo usuário: XML/dados 0, comprovantes 267, pendentes 4, bloqueados 1.614 e sem confirmação datada/classificação 1.520. Os três últimos valores são etapas, não notas distintas.

## Por que o gráfico mudou

O filtro da captura é **01–10/09**, enquanto a primeira captura da investigação abrangia agosto e setembro. Na consulta atual, Vedacit tem 0 XMLs e 267 comprovantes confirmados em setembro. No intervalo **11/08–10/09**, os registros ativos com confirmação datada mostram **75 XMLs e 2.133 comprovantes**; o último dia com XML confirmado nessa série é 20/08. As duas consultas retornaram HTTP 200.

Além do período diferente, a tela antiga copiava o total geral para ambas as etapas. A tela nova usa status/data próprios e deduplicação, deixando sucessos sem data em conferência. Os números antigos não demonstravam transmissão de XML. Não houve limpeza ou exclusão de dados nesta verificação.

## O worker ainda não executa a etapa XML

- API iniciada às 21:22:46, PID 37668; worker às 21:22:49, PID 23416. Ambos apontam para `target/satelite-0.0.1-SNAPSHOT.jar`, hash `6C5976AE48C418D4D8C5E2408E035DA49218B9F604C9D31A3B67234338DCADF6`.
- O cadastro efetivo PM2 não contém `WORK_SFTP_CLIENTES_XML_ENABLED`. `WorkSftpClientesRunner` usa `false` quando essa configuração está ausente. Também não há sobreposição de `SFTP_RODOGARCIA_ENABLED` no PM2, e o `.env` mantém esse valor em `false`.
- `VEDACIT_SEND_CTE_XML_ENABLED=true` no `.env` não basta: o worker não entra no fluxo XML quando o primeiro interruptor está desligado.
- O arquivo local `ecosystem.config.js` contém as configurações novas habilitadas, mas elas **não constam do cadastro efetivo do worker no PM2**. O reinício por nome aproveitou o cadastro antigo. A orientação anterior de iniciar os dois nomes foi insuficiente para ativar XML.
- O ciclo efetivo, confirmado tanto no log completo do ciclo quanto no endpoint de auditoria, foi de **21:23:14 a 21:24:00**: conexão SFTP OK, 3.108 comprovantes reconhecidos, um selecionado, **zero enviados**, um pendente, 820 bloqueios e 19 resultados ambíguos. O selecionado não tinha comprovante do CT-e exato disponível. Não existe resumo `[WORK-SFTP-CLIENTES][XML]` nesse ciclo, coerente com a configuração desabilitada.
- O worker saiu com código 0 e está em `waiting restart`, com intervalo PM2 de 30 minutos. Próxima execução estimada: 21:54. Esse estado é a espera programada; não comprova processamento contínuo de XML.
- O dreno de comprovantes já tem padrão `true` no pacote novo. A baixa vazão deste ciclo não decorre de atingir dez itens: só um item estava elegível. A etapa XML desligada mantém documentos bloqueados.

## Pendência concreta

Aplicar ao cadastro efetivo do worker as configurações já preparadas (`WORK_SFTP_CLIENTES_XML_ENABLED=true` e `SFTP_RODOGARCIA_ENABLED=true`, preservando a confirmação XML habilitada), revisar o primeiro lote e acompanhar o resumo XML e os aceites SOAP. O retorno dos documentos antigos exige também a recuperação dirigida/cursores descritos em `correcao-fluxo-vedacit-2026-09-10.md`; não se deve prometer que ativar a etapa, sozinho, reconcilie todas as planilhas. Não alterar sucessos divergentes nem liberar timeouts para reenvio automático.

Esta rodada executou somente consultas HTTP locais, leitura de logs/configurações e documentação. Não alterou flags, processos, SQL ou arquivos remotos, nem iniciou envios. Evidências sanitizadas em `target/verificacao-pos-reinicio-20260910-2127/`: respostas dos dois períodos, histórico de ciclos e log do ciclo atual com chaves removidas.
