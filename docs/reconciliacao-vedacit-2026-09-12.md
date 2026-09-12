# Revisão automática de pendências Vedacit — 12/09/2026

**Existe reconciliação parcial, mas a operação atual não revisa automaticamente tudo que falhou ou ficou sem confirmação.** Conferência do código, configuração efetiva PM2, `.env`, API local e SQL somente leitura, encerrada às 18:40 BRT.

## O que existe e quando funciona

| Mecanismo | Quando | Alcance real |
|---|---|---|
| `WORK-SFTP-CLIENTES` | Um ciclo, seguido de espera PM2 de 30 minutos e nova inicialização | Relê o inventário do cliente, cruza o par NF-e/CT-e com histórico local e tenta comprovantes elegíveis. Não consulta a Vedacit para confirmar todos os recebimentos duvidosos. |
| Recuperação XML no fluxo incremental | No ciclo XML, antes de avançar as ocorrências; limite padrão de dez e espera de 30 minutos entre tentativas do documento | A versão instalada exige falha de obtenção reconhecida e tentativa datada; os 79 inventários nunca tentados ficam fora. O candidato corrige essa seleção, mas não está instalado. |
| Comprovantes com falha técnica no worker | Após esgotar a fila normal, sem erro impeditivo no turno | Seleciona `PENDENTE_TECNICO` do cliente, com identidade e arquivo no inventário; até dez por seleção. Timeouts ambíguos e recusas de negócio ficam excluídos. |
| Repescagem noturna | Configurada para **23:30, America/Sao_Paulo**, mas **desligada** | Rotina limitada a certas falhas técnicas e tentativas abaixo de cinco; até cem registros no lote, priorizando XML. Não é uma auditoria de todos os estados. |
| Repescagem genérica ao fim do orquestrador | **Desligada** nos dois processos atuais | Também não substitui uma conferência geral de recebimentos e documentos antigos. |

O horário noturno vem de `ETL_NIGHTLY_RETRY_CRON=0 30 23 * * *`. Tanto `Satelite-API-19090` quanto `WORK-SFTP-CLIENTES` recebem explicitamente `--APP_NIGHTLY_RETRY_ENABLED=false`, `--APP_ETL_REPESCAGEM_ENABLED=false` e `--APP_SCHEDULER_ENABLED=false`. O `true` do scheduler no `.env` é sobreposto pelos argumentos PM2.

O worker termina a JVM ao concluir o ciclo. Assim, apenas ligar a flag noturna nele não garante execução às 23:30: ele pode estar encerrado durante a espera. Essa rotina requer definição explícita de quem mantém e executa o agendamento.

## O ciclo está funcionando

O ciclo **1009** começou às **18:34:01** e terminou às **18:40:16**, com conexão `OK`, 3.246 arquivos válidos e 38 rejeitados. Selecionou um comprovante, que permaneceu pendente; nenhum envio novo. Avaliou uma ocorrência XML já processada, sem novo envio XML e sem erro nessa etapa.

O ciclo anterior terminou às 18:03:39. O seguinte começou às 18:34:01, confirmando a espera de 30 minutos mais a inicialização. A ausência de novos envios não significa ausência de execução. Percorrer novamente o inventário também não significa que todos os bloqueios passaram por uma consulta documental nova.

## O que continua sem resolução automática

| Grupo atual | Por que não se resolve na revisão existente |
|---|---|
| **79 XMLs bloqueados** | Nunca tentados, sem data e mensagem XML: não entram no filtro instalado. Na conferência anterior, nenhum XML correlacionado estava no SFTP e o download ESL devolveu 401. A correção candidata permite revisitar, mas não produz o documento ausente nem corrige acesso externo. |
| **465 XMLs a conferir** | Há marca histórica de sucesso sem data. O cruzamento local pode aproveitar evidência existente, mas não existe varredura agendada que consulte o destino para resolver esse conjunto. A consulta Vedacit foi recusada por autorização na auditoria anterior. |
| **25 comprovantes com timeout** | Não se sabe se o destino recebeu antes de a conexão perder a resposta. O bloqueio de reenvio é necessário; falta a conferência periódica que obtenha evidência e encaminhe a solução. |
| **38 comprovantes com nome incompleto** | Falta a identificação CT-e. Repetir a leitura do mesmo nome não recupera essa informação; a pesquisa anterior na origem não trouxe a chave. |
| **Uma recusa Vedacit** | Erro de negócio permanece bloqueado, fora da repetição técnica. Exige conferência da causa e evidência da correção. |
| **Um comprovante pendente** | Log 7579, NF 233409/CT-e 53102: o arquivo da mesma nota disponível pertence ao CT-e 52321. A revisão procura novamente, mas não pode substituir um documento pelo outro. |
| **Erros arquivados** | Não retornam à fila ativa por passagem do tempo. A auditoria anterior delimitou 38 linhas/26 CT-es sem sucesso histórico, ainda aguardando recuperação dirigida. |

Os **143 comprovantes bloqueados** são 79 dependências XML + 38 arquivos incompletos + 25 timeouts + uma recusa. Os 79 aparecem nas duas etapas; não são 79 documentos adicionais. A API reconferida mantém um comprovante pendente, 79 XMLs bloqueados e 465 XMLs a conferir. O ajuste anterior de 503 para 465 retirou 38 linhas técnicas da contagem XML, sem envio documental.

O SQL atual encontrou **zero comprovantes técnicos ativos** e **zero linhas no seletor principal da repescagem noturna XML**. O filtro deste último exige `ERRO_DESTINO`; os 79 estão em `PENDENTE_ORIGEM`. Portanto, habilitar apenas a rotina das 23:30 não resolve os grupos acima. O zero do seletor principal não representa execução do fallback histórico nem do serviço noturno.

Há ainda uma fragilidade a corrigir antes de usar essa rotina: suas duas consultas principais não excluem arquivados antes de limitar o lote. O serviço recusa esses registros depois, protegendo o envio, mas eles poderiam ocupar vagas sem trabalho útil. Não houve ocorrência desse efeito no seletor XML do corte atual, que retornou zero.

## Lacuna registrada

Falta uma revisão periódica que percorra pendências, bloqueios, incertezas e erros históricos, registre última/próxima conferência e o impedimento concreto, e consulte as fontes disponíveis por identidade exata. Consulta e nova tentativa de envio precisam de decisões separadas: sucesso anterior e timeout não podem ser liberados apenas porque ficaram antigos. A revisão deve avisar quando depende de arquivo, correção cadastral ou permissão externa, e não encerrar silenciosamente com zero elegíveis enquanto há centenas aguardando solução.

Esta entrega é uma auditoria: nenhum código de aplicação, configuração, processo ou estado SQL foi alterado. O pacote operacional continua `2F66CD9CEABB1A11B0168EB53EBCD3F5C0C15DE60FE4EE0CFD4D77ECB59B8ECB`; o candidato `8F5E86BF...` permanece separado.

## Fontes e validação

- Código: [worker](../src/main/java/com/example/satelite/services/etl/WorkSftpClientesRunner.java), [repescagem e conciliação local](../src/main/java/com/example/satelite/services/etl/EtlRepescagemService.java), [agendamento noturno](../src/main/java/com/example/satelite/services/etl/RepescagemNoturnaVedacitScheduler.java), [seletores](../src/main/java/com/example/satelite/repositories/LogIntegracaoRepository.java) e [proteções por registro](../src/main/java/com/example/satelite/services/etl/EtlRegistroService.java).
- [SQL diagnóstico](../database/sql/diagnostico/20260912_reconciliacao_vedacit.sql): somente SELECT, exclusivamente `SATELITE_TMS_AUDITORIA`, executado com rollback; corte final com 18 linhas de resultado, sem chaves fiscais completas.
- Evidências locais em `target/reconciliacao-vedacit-20260912/`: `runtime.json` (somente flags permitidas, sem credenciais), `sql.json` e `indicadores.json` (HTTP 200).
- Evidências documentais e consultas externas anteriores: [conferência dos 465 e correções](resolucao-vedacit-2026-09-12.md). Nenhuma consulta externa nova nem envio foi disparado por esta auditoria; o worker já ativo seguiu sua operação normal.
