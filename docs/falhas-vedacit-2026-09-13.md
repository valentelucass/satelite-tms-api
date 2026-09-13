# Conferência das falhas Vedacit — 13/09/2026, 01:05–01:10 BRT

O processamento continua executando, mas os documentos retidos não avançaram. A recusa de acesso ao XML na ESL persiste. O SFTP conectou normalmente nos oito ciclos entre 12/09 às 19:57 e 13/09 às 00:55. Não houve envio de XML nem comprovante nesses oito ciclos.

## O que os números representam

- **79 XMLs pendentes:** são exatamente os mesmos 79 IDs classificados como bloqueados na auditoria de 12/09, antes da recuperação. Comparação individual com `target/auditoria-vedacit-20260912/pendencias-por-log.csv`: zero diferença. Todos foram avaliados; nenhum foi confirmado. A mudança de bloqueado para pendente decorre do estado `ERRO_DESTINO` após a avaliação, não da liberação do acesso.
- **80 avaliações com erro nos oito ciclos:** dez por ciclo, sobre 79 CT-es distintos. O log 8818/CT-e 53799 foi avaliado duas vezes, às 19:59:59 e 00:49:49; os demais, uma vez. O rodízio percorreu a fila inteira e começou a revisitar o conjunto.
- **Oito recusas HTTP 401 e 72 avaliações em espera:** em cada ciclo, a primeira consulta de XML à ESL foi recusada; as nove seguintes ficaram retidas pelo intervalo de espera de autenticação, sem nova consulta ESL. Não são oitenta envios recusados pela Vedacit.
- **465 XMLs a conferir:** saldo histórico sem confirmação datada, separado dos 79 acima. Não aumentou. A recusa dos métodos SOAP de consulta foi comprovada no rastreio de 12/09; esta conferência não provocou novas chamadas externas.
- **Um comprovante pendente:** log 7579, NF 233409/CT-e 53102, ainda sem o arquivo exato. O último ciclo voltou a registrar ausência no SFTP.

O banco guarda o último resultado de cada documento. Por isso, o estado atual contém sete linhas com HTTP 401 e 72 em espera, enquanto o histórico dos logs registra oito recusas: a primeira recusa do log 8818 foi substituída por uma avaliação posterior em espera. A soma das tentativas documentais é 80; o indicador por documento é 79. No gráfico atual, 59 estão datados em 12/09 e vinte em 13/09; a reavaliação também atualiza a data usada pelo indicador. Não somar o histórico de ciclos como se fossem documentos diferentes.

## Os 143 comprovantes bloqueados

| Quantidade | Motivo |
| --- | --- |
| 79 | Dependem do XML/dados ainda não confirmados. |
| 38 | Arquivos rejeitados por ausência de CT-e; o rastreio anterior também confirmou falta de vínculo na origem. |
| 25 | Envios antigos com resposta incerta por timeout; continuam protegidos contra reenvio. |
| 1 | Recusa do destino já identificada, log 9420. |
| **143** | **Total sem aumento nos oito ciclos.** |

Os 79 XMLs e os 79 comprovantes dependentes se sobrepõem. Não são 158 documentos independentes. Os ciclos tiveram zero erro novo de comprovante; continuam revisando o único pendente normal. A auditoria não liberou nenhum timeout nem alterou um aceite.

## Processos e revisão da madrugada

API PID 22560 online desde 12/09 às 20:55, quatro endpoints de auditoria HTTP 200. Worker finalizou às **00:55:25**, sem ficar preso; o PM2 está em `waiting restart`, com próxima execução estimada para **01:25:25**. O PID 50672 ainda exibido pelo cadastro já não existe no Windows. Seus seis reinícios desde o corte anterior correspondem aos seis ciclos seguintes, todos com início e fechamento registrados.

Supervisor noturno **único**, cadastro 191/PID 28340, online desde 21:01, zero reinícios, confirmado também pelos processos Windows. A janela é **02:00–06:00 America/Sao_Paulo**. Às 01:10 ainda não havia execução noturna; histórico vazio antes da janela é esperado. Não se pode atestar o resultado da primeira madrugada antes de ela ocorrer.

O pacote normal mantém SHA-256 `A43925272F206711C802084875DEBED0AF5AC5722D0C0E7B9392D82266226303`. Não há evidência de nova queda da API, duplicação do supervisor ou falha de conexão SFTP neste recorte. Os turnos de comprovante com zero selecionados também percorrem o inventário em partes; não representam novo envio nem, isoladamente, travamento.

## Problemas que continuam exigindo ação

1. **Acesso ao XML:** autorizar o download na ESL para a credencial configurada ou disponibilizar os arquivos oficiais ausentes no SFTP. Repetir ciclos não resolve a recusa HTTP 401.
2. **Consulta no destino:** liberar os métodos SOAP identificados no [rastreio de acesso](rastreio-vedacit-2026-09-12.md) para permitir a conferência dos históricos e dos envios incertos. A rotina noturna pode registrar e revisar o impedimento, mas não conceder essa permissão.
3. **Clareza do painel:** a palavra “falha” reúne recusa de acesso e espera, `xmlPendentes=0` representa somente o resultado do ciclo, e a passagem para 79 pendentes/zero bloqueados não significa que o acesso foi resolvido. Esta limitação continua registrada para correção; os indicadores não foram alterados nesta auditoria.
4. **Documentos da origem:** permanece necessário o comprovante do CT-e 53102 e o vínculo correto dos 38 arquivos sem CT-e.

## Evidências e validação

SQL somente leitura, exclusivamente em `SATELITE_TMS_AUDITORIA`, executada com rollback: [20260913_auditar_falhas_vedacit.sql](../database/sql/diagnostico/20260913_auditar_falhas_vedacit.sql), 97 linhas de resultado. Respostas API, eventos sanitizados por ciclo/documento, comparação dos IDs antigos, runtime e verificações cruzadas em `target/auditoria-falhas-20260913-0105/`.

Conferências aprovadas: 79 documentos/80 avaliações; oito ciclos de dez; oito recusas/72 esperas; zero envio; 143 bloqueios de comprovante; paridade API/SQL/logs; conjunto de IDs inalterado. Nenhum Java, configuração, credencial, JAR ou processo foi alterado. A bateria do pacote permanece a de 12/09 (430 aprovados, zero falhas/erros, três manuais desabilitados); não foi repetida para uma auditoria somente leitura.
