# Reconciliação noturna Vedacit

Supervisor próprio, residente e sem porta HTTP, para revisar diariamente os documentos Vedacit entre **02:00 e 06:00, America/Sao_Paulo**. O processo é independente do `WORK-SFTP-CLIENTES`, que termina entre ciclos.

**Ativado em 12/09/2026 às 19:29 BRT:** `VEDACIT-RECONCILIACAO-NOTURNA` online, PID inicial 47592, sem reinícios; cadastro salvo no PM2. Primeira janela: **13/09, a partir de 02:00**. A ativação do novo supervisor atende ao pedido de manter a revisão diariamente de madrugada; nenhum processo existente foi reiniciado. A revisão real da primeira madrugada ainda precisa ser aferida pelo histórico.

## Comportamento

- O supervisor verifica a janela a cada minuto. Se iniciar às 03:00, ainda trabalha naquela madrugada. Fora da janela não consulta fontes nem envia documentos.
- Cada varredura salva o maior ID inicial e avança por ID em páginas de cem registros. Inclui erros, pendências, bloqueios, sucessos sem data e erros/incertezas arquivados. Documentos já concluídos e datados não são reenviados.
- Há uma execução por data. O resultado de cada item e o avanço do cursor são gravados na mesma transação. Reinício retoma o checkpoint; uma revisão incompleta de outra noite tem prioridade. Às 06:00 não começa outro documento e marca a espera pela próxima madrugada. Uma chamada já iniciada pode terminar depois desse horário, respeitando seu timeout.
- Um lock de sessão SQL impede dois supervisores simultâneos. As ações por documento compartilham os locks e a releitura de estado do worker normal. Antes de tentar recuperar XML, o resultado desconhecido é persistido: uma queda não permite repetir cegamente o envio.
- Os 79 inventários XML nunca tentados entram na conferência. XML com falha comprovada de obtenção pode ser recuperado; sucesso histórico, recusa e resultado incerto permanecem protegidos. Comprovantes elegíveis usam apenas o arquivo SFTP do par correto e exigem XML confirmado.
- Recusas de acesso e falhas de infraestrutura suspendem a fonte/operação afetada naquela varredura. Os demais itens continuam recebendo diagnóstico. O bloqueio sobrevive a reinícios e é reavaliado na madrugada seguinte. Arquivo ausente e recusa cadastral individual não suspendem todo o lote.
- Aceite permanente local pode corrigir classificação residual sem retransmissão. A revisão de arquivados não os reativa nem remove suas restrições.

## Limites que continuam explícitos

Consultar CT-e no destino confirma sua presença somente quando a chave retornada é a exata. `DataRetorno` e `DataEmissao` não são usadas para inventar a primeira data de recebimento. Uma presença confirmada sem data fica registrada como `XML_PRESENTE_NO_DESTINO_DATA_A_CONFERIR`.

O contrato `BuscarCanhotoPorChaveNFe` retorna arquivo/extensão, sem CT-e e primeira data. Encontrar esse arquivo gera evidência incompleta (`COMPROVANTE_NFE_PRESENTE_VINCULO_CTE_A_CONFERIR`); não libera timeout nem comprova o par por aproximação. O arquivo retornado não é salvo no banco ou relatório.

Os 465 XMLs sem data, 25 timeouts, 38 nomes incompletos e erros arquivados passam pela revisão. A automação não fabrica chave ausente, acesso negado ou informação histórica. Quando essas dependências persistem, registra o impedimento e a próxima revisão. **Varredura concluída significa que os registros foram conferidos, não que todos foram resolvidos.**

## Auditoria e acompanhamento

A V27 cria apenas `tb_reconciliacao_vedacit_execucao` e `tb_reconciliacao_vedacit_item`, com histórico por execução, ID de log, última/próxima revisão, resultados XML/comprovante, ação e motivo técnico sem documentos ou credenciais. Não modifica dados anteriores. O atualizador tem modo restrito `database/subir_database.bat --reconciliacao`, sem repetir saneamentos históricos.

Após cada página, o supervisor escreve `logs/reconciliacao-vedacit/AAAA-MM-DD-ID.md`, com resumo das causas. Os detalhes ficam disponíveis na auditoria por registro. O pacote inclui estas consultas HTTP somente leitura, disponíveis quando a API usar a nova versão:

- `GET /api/auditoria/vedacit/reconciliacoes`: últimas trinta execuções e quantidade revisada.
- `GET /api/auditoria/vedacit/reconciliacoes/{id}/resumo`: contagens por etapa/resultado/ação.
- `GET /api/auditoria/vedacit/reconciliacoes/{id}/itens?depois=0&tamanho=100`: página por ID de log, limitada a duzentos itens, sem chaves fiscais completas.

Uma ausência de execução deve ser conferida pelo histórico e pelo status PM2. O supervisor depende de Windows/PM2 ativos. Se o computador ficar desligado durante toda a janela, a revisão ocorre na próxima madrugada disponível; não há transmissão fora da janela para compensar o atraso.

## Pacote e ativação

**Aceite da subida de 12/09 às 19:57:** API PID 28604 carregou o pacote novo e respondeu HTTP 200 nas quatro consultas de auditoria verificadas, incluindo reconciliação. Worker PID 24916 encerrou o primeiro ciclo às **20:06:25**, em 8m29s, registrando `FALHA/XML_RETIDO`: onze XMLs avaliados, um já processado, um com HTTP 401 da origem e nove aguardando acesso; nenhum XML enviado. O comprovante sem arquivo exato continua pendente, sem novo erro de comprovante ou liberação dos 25 timeouts. Após o término, PM2 entrou em `waiting restart`, com próxima execução estimada às **20:36**. API e supervisor noturno permaneceram online; não houve novos reinícios no intervalo observado. Evidências em `target/aceite-subida-20260912/`. A auditoria somente observou a execução iniciada pelo usuário.

O saldo XML passou a dez pendentes, 69 bloqueados e 465 a conferir, porque a recuperação nova alcançou dez dos inventários antes nunca tentados; a autorização da origem continua impedindo o download. Comprovantes permanecem com um pendente e 143 bloqueados. A revisão noturna ainda não ocorreu: supervisor pronto para 02:00–06:00 e histórico vazio antes da primeira janela. O processo noturno mantém a cópia anterior de mesmo hash até reler o ecosystem principal, conforme explicado abaixo.

A configuração única é [ecosystem.config.js](../ecosystem.config.js), com **Satelite-API-19090**, **WORK-SFTP-CLIENTES** e **VEDACIT-RECONCILIACAO-NOTURNA**. Os três apontam para `target/satelite-0.0.1-SNAPSHOT.jar`. O pacote completo já foi instalado pelo usuário em **12/09 às 19:54:23**, SHA-256 `05A4BC9C41A9B4B5E2D110E98D4F16DC07F67EA584BB4010A5E12BFC971A4040`; backup anterior confirmado em `target/backup-pacotes/`. Evidência em `target/pacote-reinicio-20260912-194944/instalacao.json`.

Por solicitação do usuário, foram removidos o ecosystem separado, o aplicador de pacote e o ativador exclusivo. A configuração principal passou a ser versionada, sem credenciais. A operação segue pelo ecosystem existente; não há instalador adicional. V26 e V27 já foram aplicadas, e essa consolidação não exige recompilar ou atualizar o banco.

A próxima subida pelo usuário deve ler `ecosystem.config.js` para atualizar também o caminho do JAR no cadastro noturno. Reiniciar somente pelo nome conserva os argumentos anteriormente salvos no PM2. O supervisor já em execução continua usando a cópia de mesmo hash em `target/reconciliacao-noturna/` até essa atualização; ela foi preservada. Nenhum processo foi parado, iniciado ou reiniciado durante a consolidação.

As configurações da API e do worker foram preservadas integralmente. O supervisor conserva nome, janela, cadência, flags e isolamento; mudou apenas o caminho do JAR para o comum. Os schedulers antigos, runners de ciclo único e demais destinos continuam desligados no perfil noturno. A configuração recusa combinação com API passiva, worker de ciclo único ou comprovante com fallback ESL. A persistência do cadastro PM2 após reinício do Windows segue a operação humana atual.

Sintaxe JavaScript e seis verificações da consolidação aprovadas: três nomes únicos; API/worker iguais ao arquivo anterior; perfil noturno igual, exceto pelo caminho do JAR; mesmo pacote validado para todos; Java disponível/instância única; remoção dos quatro arquivos redundantes. Evidência em `target/consolidacao-ecosystem/validacao.json`. Nenhum código Java mudou; a bateria completa de 421 testes aprovados permanece válida para o pacote instalado.

Parâmetros principais: `VEDACIT_RECONCILIACAO_ENABLED` (padrão desligado), `START=02:00`, `END=06:00`, `PAGE_SIZE=100`, `PACING_MS=1000`, `POLL_MS=60000` e `RECOVERY_ENABLED=true`, todos com o prefixo `VEDACIT_RECONCILIACAO_`. Desligar `RECOVERY_ENABLED` mantém consultas e auditoria, sem ações de recuperação ou correção de aceite local.

## Validação desta entrega

Evidências em `target/reconciliacao-noturna-validacao/`. O ensaio SQL usa o repositório Java real e transação com rollback, percorrendo todas as páginas, verificando checkpoint, limite, unicidade diária, persistência/reabertura dos bloqueios e resumo. Os testes Java cobrem horários, retomada, concorrência, isolamento, erros por item, ausência de confirmação, identidade divergente e proteção após queda. A V27 foi ensaiada duas vezes na mesma transação antes da aplicação restrita; logs e aceites anteriores foram preservados.

O manifesto final do pacote informa hash, suíte validada e situação de ativação. Código empacotado e configuração disponível não equivalem a supervisor já ativo no PM2.

Resultado final: **424 testes, 421 aprovados, zero falhas/erros e três manuais desabilitados**. A primeira suíte completa detectou dois erros no contexto de teste sem banco, que ainda não substituía o novo repositório por mock; a configuração do teste foi completada e as suítes seguintes passaram. Ensaio SQL final: **4.506 registros em 23 páginas**, incluindo 3.192 arquivados e 79 inventários XML nunca tentados, com rollback confirmado. As 1.269 entradas de aplicação do JAR coincidem com as classes/recursos testados. Inicialização real do candidato aprovada em processo isolado, com recuperação desligada e primeiro disparo adiado, antes de ativar o supervisor operacional.

JAR validado: SHA-256 `05A4BC9C41A9B4B5E2D110E98D4F16DC07F67EA584BB4010A5E12BFC971A4040`, instalado também no caminho normal às 19:54. O JAR anterior `2F66CD9CEABB1A11B0168EB53EBCD3F5C0C15DE60FE4EE0CFD4D77ECB59B8ECB` ficou preservado em backup. Evidências da ativação inicial do supervisor e persistência: `runtime.json`, `ativacao.log` e `persistencia-pm2.log` na pasta de validação.

Bateria completa repetida a pedido do usuário em **12/09/2026 às 19:39 BRT**, com o mesmo resultado: **424 testes, 421 aprovados, zero falhas/erros e três manuais desabilitados**. Os três dependem de imagens locais ou serviços externos. Compilação Java 17 aprovada, rede da JVM de testes bloqueada e cobertura de linhas do código próprio em **71,72%**. Evidência: `target/unit-tests/coverage-20260912-193610-898/summary.json`; detalhes e cobertura na mesma pasta. Os dois hashes acima foram reconferidos após a suíte e permaneceram iguais. Não foi necessária correção adicional; o resultado dos testes não substitui a aferição da primeira madrugada real.
