# Tabelas de ciclos sem atualização — diagnóstico e correção

## Causas comprovadas

Em 11/09, o worker iniciado às 17:12 continuava enviando documentos às 18:27, mas a última linha em `tb_work_sftp_cliente_execucao` ainda era a 985, finalizada às 16:53:30. A API Satélite retornava exatamente essa linha. O painel de pendências consulta a auditoria por documento e, por isso, avançava enquanto os ciclos permaneciam iguais.

1. **Satélite:** `WorkSftpClientesRunner` só registrava o ciclo depois de terminar toda a varredura XML e o dreno dos comprovantes. A alternância por turnos funcionava, mas não publicava resultados parciais. O quadro mostrava uma execução antiga e uma estimativa de próximo ciclo já ultrapassada.
2. **Dashboard:** ciclos, indicadores e tabela de integrações usavam `OPERATIONAL_QUERY_POLLING_OPTIONS`, com atualização de 30 minutos mais até um minuto de variação. `staleTime: 60000` não é um timer de atualização. A API do Dashboard encaminha a resposta do Satélite como JSON; não há cache de ciclos nesse cliente. A causa já é reproduzida consultando diretamente o Satélite e o banco, sem depender do proxy.
3. **Publicação:** a UI em produção ainda servia o build `20260911031155`, de 03:12 UTC. As alterações de XML preparadas anteriormente não estavam nessa publicação.

Evidências: `target/diagnostico-tabelas-20260911/`, incluindo SQL somente leitura versionado em `database/sql/diagnostico/20260911_atualizacao_tabelas_ciclos.sql`. Às 18:23, a API de etapas já informava 404 XMLs e 761 comprovantes confirmados no dia, enquanto a API de ciclos continuava em 16:53. Não foi criada sessão nem contornada autenticação do Dashboard; a inspeção do proxy foi de código e a origem foi conferida diretamente.

## Correção preparada

- O ciclo ganha um identificador único e uma linha desde o início, com `EM_EXECUCAO`, término nulo e horário da atualização. Os turnos atualizam essa mesma linha; o fechamento preenche o término. Uma atualização atrasada não reabre ciclo já finalizado.
- XML acumula seus resultados antes de ceder o turno. O worker publica o parcial antes da troca e depois do turno de comprovantes. Contagens das duas etapas permanecem separadas.
- Último ciclo é escolhido pelo início. Histórico inclui ciclos abertos pelo início e finalizados pelo término, mantendo uma linha por execução. Registros antigos permanecem preservados.
- A tela consulta a cada minuto enquanto aberta, suspende polling em segundo plano e consulta novamente ao retornar. Mostra início, última atualização, números parciais e status em andamento. A estimativa do próximo ciclo só aparece após finalizar.
- Mais de dez minutos sem progresso geram aviso de atualização antiga. Não é inventado término nem inferida parada; um processo interrompido preserva sua última posição auditada.
- Dicionário de KPIs alinhado à nova interpretação. Os fluxos de envio, credenciais e regras de retenção não foram alterados por esta correção.

## Validação e publicação

Backend: **365 testes executados, 362 aprovados e três manuais ignorados**, sem falhas, em Java 17 com rede/banco externo bloqueados. Testes cobrem identificação única, abertura sem término, parcial/fechamento, falha de abertura antes de conexão externa, resultados XML antes da troca e proteção contra reabertura de ciclo fechado. Evidência: `target/unit-tests/coverage-20260911-182240-056/summary.json`.

Frontend: **18 testes aprovados**, TypeScript, lint e build aprovados. O teste com relógio controlado confirma nova consulta dos dois quadros após 60 segundos e substituição das contagens, sem finalização/agenda inventadas. Seis cenários visuais (390/1265/1920 px, claro/escuro) passaram sem transbordamento ou exceções. Evidência: `../etl-dash/dashboards/frontend/.tmp/progresso-visual/`.

Migration **V23 aplicada** exclusivamente em `SATELITE_TMS_AUDITORIA` via `database/subir_database.bat --rodizio`, com segunda execução sem erro. Baseline V16 alinhado. SQL real confirmou `fim_em` anulável e as novas colunas `execucao_id`/`atualizado_em`. O JAR anterior continua compatível com a alteração aditiva; a nova escrita de parciais depende de publicar o novo pacote.

Pacotes prontos, ainda sem substituir os processos em execução:

- Satélite: `target/progresso-ciclos-20260911/satelite-0.0.1-SNAPSHOT.jar`, SHA-256 `E185881B02CA8E1E59A10B5C381F60E49D01E99ECDB8719F60863BF61ED85A45`. 1.253 classes/recursos comparados com a saída testada.
- UI: `../etl-dash/dashboards/frontend/.tmp/quality-build/20260911-progresso/dist`, build `20260911-progresso-final`, incluindo `build-info.json` e sem sourcemaps. Substitui o candidato anterior de rodízio.
- A API do Dashboard não requer alteração para encaminhar os novos campos. Publicar a UI também é necessário para o polling de um minuto e os rótulos de progresso.

O JAR operacional permanece `5FDE1735...`; worker/API Satélite e serviços Dashboard não foram parados ou reiniciados. A troca de artefatos deve ocorrer na janela do operador. O ciclo que já estava aberto na versão anterior não ganha retrospectivamente uma linha parcial. Após a publicação, conferir no primeiro ciclo real a mesma linha passando de andamento para finalizado.
