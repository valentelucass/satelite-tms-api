# Diagnóstico e apresentação dos bloqueios XML — 13/09/2026

O painel repetia “FALHA” e “dez falhas XML” para ciclos que conectavam ao SFTP e terminavam, mas não conseguiam obter os XMLs na origem. O resumo também mostrava zero pendentes XML, embora houvesse 79 documentos aguardando solução. A correção preserva os resultados auditados e distingue a causa apresentada ao operador.

## Resultado da madrugada

Primeira execução real, ID 3, de **02:00:31 a 04:33:51 BRT**. Foram revisados **4.506 logs distintos**, incluindo 3.192 arquivados, sem reativá-los. A conferência SQL não encontrou candidato elegível sem revisão dentro do limite da execução. Os 25 timeouts ativos foram revisados e continuaram retidos. Não houve documento recuperado/enviado; houve recusa de acesso ao download XML e às consultas XML/comprovante no destino.

Próxima revisão registrada: **14/09 às 02:00**. Os 558 resultados de consulta XML sem permissão e 403 de comprovante incluem o histórico/arquivados; não são novas chamadas HTTP nem novos bloqueios ativos. A rotina suspende a fonte após a recusa e registra a dependência nos demais itens afetados.

Consulta somente leitura/rollback: `database/sql/diagnostico/20260913_conferir_primeira_reconciliacao.sql`. Evidências API/SQL em `target/painel-bloqueios-20260913/noturno-*.json`; relatório gerado pelo supervisor em `logs/reconciliacao-vedacit/2026-09-13-3.md`.

## Mudança no Satélite

- Resultados documentais auditados com `ORIGEM_XML_HTTP_401`, `ORIGEM_XML_HTTP_403` ou `ORIGEM_XML_AUTENTICACAO_EM_ESPERA` agora conservam o motivo tipado `RETIDO_ACESSO_ORIGEM` ao subir para o resumo do ciclo.
- `ResultadoPagina` e `ResultadoDestino` acumulam esse subconjunto sem retirar as avaliações da contagem existente de erros/retenções. A classificação usa o retorno desta avaliação, nunca a causa antiga ou o saldo atual.
- Se todas as falhas XML do ciclo forem desse tipo, `motivoFalha` recebe `XML_ACESSO_ORIGEM`. Mistura com outras falhas/retenções usa `XML_FALHAS_MISTAS`; falha crítica da execução usa `XML_PROCESSAMENTO`. Uma causa de comprovante já registrada mantém precedência.
- A retenção continua permitindo avanço do cursor quando já há auditoria válida, sem liberar reenvio, alterar seleção, intervalo, datas, aceites ou proteções SOAP. O ciclo conserva `statusCiclo=FALHA` e código de saída sem sucesso.
- Nenhuma coluna nova, migration ou reclassificação histórica é necessária. O motivo específico aparecerá nos ciclos produzidos pelo pacote novo.

## Mudança no Dashboard

| Situação registrada | Apresentação |
| --- | --- |
| Ciclo finalizado, conexão OK, zero erros de comprovante e XML com recusa de acesso explícita | **XML sem acesso à origem**, com orientação para liberar o acesso ou fornecer os XMLs no SFTP. |
| Mesmas condições, motivo antigo `XML_RETIDO`, sem causa detalhada do ciclo | **XML com impedimentos**; informa que há retenções ou erros, sem inventar que todos foram HTTP 401. |
| Erro crítico, causas mistas, timeout, falha de comprovante/conexão ou motivo desconhecido | Mantém destaque de **falha**. Ausência da medição de erro de comprovante não é tratada como zero. |
| Execução aberta | Continua **Em andamento**, com a verificação de atualização existente. |

XML mostra confirmações, avaliações sem conclusão e avaliações já processadas. O valor zero de pendência da origem deixou de aparecer como se fosse o saldo acumulado; quando positivo, aparece como avaliações aguardando documento na origem. O dicionário explica que o saldo está em Pendências atuais por etapa. Nenhum número foi diminuído ou convertido em sucesso. O filtro passou a se chamar “Com impedimentos ou falha”, preservando a consulta `status=FALHA` e a paginação no servidor.

Avisos usam as cores de atenção do tema com contraste para leitura. `StatusBadge` mantém a aparência anterior para os outros usos; a tonalidade de atenção é opcional e usada nesses dois diagnósticos do ciclo.

## Validação e pacote

- Satélite: 48 testes direcionados; bateria completa com **441 testes, 438 aprovados, zero falhas/erros, três manuais desabilitados**. Rede bloqueada, Java 17. Cobertura de linhas própria: 72,06%. Evidência `target/unit-tests/coverage-20260913-102417-290/summary.json`.
- Pacote candidato: `target/unit-tests/coverage-20260913-102417-290/satelite-0.0.1-SNAPSHOT.jar`, SHA-256 `78B8B50695186C597C5672929303256D0F69E397D10243B29245050A1830DB49`. As 1.269 entradas da aplicação coincidem com classes/recursos testados; sem H2, credenciais ou classes de teste. Manifesto `target/painel-bloqueios-20260913/pacote-validado.json`.
- Dashboard: 24 testes direcionados e bateria completa de 383 testes aprovados; TypeScript, lint e build isolado. A revisão visual usa três larguras, dois temas e três situações (acesso, legado e timeout). Evidências em `../etl-dash/dashboards/frontend/.tmp/bloqueios-visual/` e arquivos `.tmp/painel-bloqueios-*`.

Build frontend: `20260913-bloqueios-xml`, com 383 testes aprovados também após o ajuste final de contraste, TypeScript, lint e 18 cenários visuais aprovados. Candidato em `../etl-dash/dashboards/frontend/.tmp/quality-build/20260913-bloqueios-xml/dist`.

## Publicação autorizada

API e worker Satélite foram parados externamente às 10:24. Após confirmar o encerramento do supervisor e ausência de JVM usando o JAR normal, pacote 78B8B506... instalado às **10:34:46**. Backup A4392527... em `target/backup-pacotes/satelite-antes-painel-20260913-1035.jar`. Os três processos foram iniciados uma vez pelo ecosystem existente: API PID 48764, worker 52556 e supervisor 51416. Supervisor único, cadastro 191/zero reinícios; a saída da JVM antiga foi confirmada antes da nova partida, evitando a corrida anterior do PM2.

Usuário autorizou explicitamente publicar/reiniciar a interface. Os 58 arquivos aprovados foram instalados em `frontend/dist-prod` às **10:35:48**, com hashes conferidos e backup integral em `frontend/.tmp/publicacao-bloqueios-20260913/backup/dist-prod`. UI PID 39644. API Dashboard 5010 permaneceu no PID 48508.

HTML, metadados e chunk do painel respondem HTTP 200; JavaScript servido é idêntico ao publicado, SHA-256 `5191868D329AAA4B59C9BAF75BE2F84FC33B2CE2F80858ECDEB7A1A3FA1ADB25`. O endereço público também retorna o build novo no JSON e cabeçalho às 10:38:29, com cache DYNAMIC. PM2 salvo e cadastros conferidos. Evidências de instalação/runtime/HTTP no diretório da entrega; nenhum ecosystem adicional, migration ou credencial alterada.

O primeiro ciclo concluiu **10:35:27–10:43:43**, em 8m16s. Resumo e histórico da API coincidem: `FALHA/XML_ACESSO_ORIGEM`, conexão SFTP OK, inventário 3.246/38, onze avaliações XML/um já processado/dez retenções/zero envio. O log confirma uma recusa HTTP 401 e nove avaliações em espera de autenticação. Comprovantes: um pendente, zero envios e zero erros novos; 118 bloqueios e 25 timeouts preservados. O motivo específico foi persistido e atende às condições da nova apresentação **XML sem acesso à origem**.

Worker terminou e aguarda o próximo ciclo estimado às **11:13:43** no PM2; seu PID antigo já não existe no Windows. API 48764, UI 39644 e supervisor único 51416 continuam online; API Dashboard 48508 preservada. Saldo documental conferido: XML 79 pendentes/zero bloqueados/465 a conferir; comprovantes um pendente/143 bloqueados. Confirmações no período permanecem 824 XML e 911 comprovantes. Evidência final: `target/painel-bloqueios-20260913/aceite-final.json`.

**Limite:** essa correção torna o problema identificável no painel. O acesso ao XML e às consultas SOAP continua dependendo da autorização nos sistemas externos; a mudança visual não remove esses impedimentos.
