# Organização dos workers e da reconciliação

**Atualização às 23:29 BRT:** proposta implementada, testada e JAR instalado com autorização do usuário. Supervisor único para quatro clientes, recuperação Vedacit preservada e conferência local dos demais; processos permanecem parados. Ver [entrega e subida](reconciliacao-clientes.md). A investigação abaixo documenta a situação anterior.

Investigação em 21/09/2026, aproximadamente 23:05 BRT. Escopo: leitura do código, cadastro PM2, hash do pacote e endpoints locais de auditoria. Nenhum processo, envio, configuração operacional ou dado de negócio foi alterado.

## Situação comprovada

- API, `WORK-SFTP-CLIENTES` e `VEDACIT-RECONCILIACAO-NOTURNA` apontam, inclusive no cadastro atual do PM2, para o mesmo `target/satelite-0.0.1-SNAPSHOT.jar`. Não existe JAR separado no cadastro noturno atual. O hash em disco é `EFF36F937715338675CCA3F52A2C4429CC2B70AFA8EC4CEA6A7C9E46AB908A2F`, instalado em 18/09. O candidato das alterações de Integrações de 21/09 ainda é distinto desse pacote.
- O worker normal executa um ciclo e encerra a JVM em [WorkSftpClientesRunner.java](../backend/java/src/main/java/com/example/satelite/services/etl/WorkSftpClientesRunner.java). O PM2 o inicia novamente após 30 minutos; `waiting restart` é compatível com esse desenho, e o contador acumulado de reinícios não mede sozinho falhas.
- O noturno é residente, sem HTTP. Verifica a janela 02:00–06:00 de São Paulo a cada minuto e processa páginas com checkpoint. Foi separado para não depender do encerramento e da espera do worker normal.
- A validação então denominada `ReconciliacaoVedacitConfig` (agora [ReconciliacaoClientesConfig.java](../backend/java/src/main/java/com/example/satelite/config/ReconciliacaoClientesConfig.java)) recusa explicitamente a combinação com `work.sftp-clientes.enabled=true`. Unificar na mesma JVM exige mudar seu ciclo de vida e essa proteção; apenas ativar as duas flags não funciona.
- A revisão noturna é mais ampla que a fila normal: inclui incertezas históricas, registros arquivados e confirmações sem data confiável. Reutiliza os serviços de envio e locks por documento, mas tem seleção, consultas de conferência e auditoria próprias. Não reativa arquivados nem autoriza reenvio de timeout por aproximação.

## Evidência de execução real

Os GETs locais `/api/auditoria/vedacit/reconciliacoes` e `/api/auditoria/vedacit/reconciliacoes/10/resumo` responderam com a execução 10, referente a 21/09: início 02:00:01 e fim 04:06:27 BRT, `CONCLUIDA`, 4.573 registros revisados. As ações foram 3.192 arquivados conferidos sem reativação, 1.311 conferidos e 70 envios retidos aguardando confirmação exata. Não houve ação de recuperação registrada nessa execução. Houve impedimentos de permissão na consulta/origem e indisponibilidade SFTP; concluir a varredura não significa resolver todos os documentos.

Os caminhos de logs informados pelo PM2 não estavam disponíveis no filesystem consultado. Os números acima vieram dos endpoints de auditoria, não da leitura desses logs.

## Organização recomendada

Conforme a orientação do usuário, se a reconciliação continuar em processo separado, ela deve ser única para todos os clientes, evitando uma JVM por integração:

| Processo | Responsabilidade |
| --- | --- |
| `Satelite-API-19090` | Consulta de auditoria e suporte ao Dashboard |
| `WORK-SFTP-CLIENTES` | Ciclos operacionais de processamento e envio |
| `RECONCILIACAO-CLIENTES` | Supervisor residente das revisões de todos os clientes habilitados |

O supervisor compartilharia agendamento, paginação, checkpoint, limites de trabalho, isolamento de falhas e observabilidade. Cada cliente teria adaptador próprio para seleção, consulta de confirmação, correlação e decisão de recuperação, além de janela, habilitação e limites próprios. A execução deve alternar páginas/lotes limitados entre clientes para que uma integração lenta não impeça as demais. Preservar locks compartilhados com o worker normal, cooldowns, aceites e a separação entre revisão, tentativa e confirmação no Dashboard.

Hoje o scheduler, repositório, tabelas e ações da reconciliação são específicos da Vedacit. Renomear o processo no PM2 não implementa suporte a PPG ou SELIA; cada adaptador precisa de contrato e validação próprios antes da habilitação. A evolução deve preservar o histórico existente e distinguir o cliente na execução/checkpoint. A migração operacional deve substituir o supervisor Vedacit somente quando o supervisor genérico estiver validado, evitando dois responsáveis pela mesma agenda.

Alternativa futura: incorporar também a agenda de revisão em um único worker residente. Isso reduz um processo, mas exige retirar o `System.exit` do fluxo normal, substituir a cadência hoje controlada pelo PM2 e garantir que uma revisão longa não bloqueie os ciclos regulares. Não é uma simples mudança de cadastro.

Esta entrega é uma análise e recomendação. Não houve refatoração Java, rename no PM2, publicação ou disparo de revisão. Não foram executados testes de envio ou consultas aos destinos externos.
