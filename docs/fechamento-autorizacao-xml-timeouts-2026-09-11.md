# Verificação dirigida de XML e timeouts — 11/09/2026

Escopo final solicitado: Vedacit, autorização do download XML e confirmação dos timeouts. Outros clientes ficam para uma evolução futura.

## Resultado verificado em ambiente real

Consultas somente leitura iniciadas às 17:02 BRT, usando as credenciais configuradas, sem enviar documentos ou iniciar o worker.

| Verificação | Resultado | Dependência para concluir |
| --- | --- | --- |
| Download ESL do XML do registro 10561 | HTTP 401 com `RODOGARCIA_MASTER_API_REST`, na rota configurada de CT-e; uma chamada, sem retry | A origem precisa liberar/corrigir a credencial desse download. Alternativamente, disponibilizar os XMLs correlacionados no SFTP. |
| Alternativa SFTP para os registros 10561–10566 | Nenhum dos seis pares CT-e/NF-e encontrou XML válido disponível, usando o cliente e o validador do candidato | Publicação dos seis XMLs corretos na pasta de origem, ou acesso ESL autorizado. |
| Confirmações locais dos timeouts | 23 registros ativos; nenhum tem sucesso datado para o mesmo par NF-e/CT-e efetivo, mesmo considerando auditorias arquivadas | Evidência do destino para distinguir recebido de não recebido. |
| Consulta SOAP `BuscarCanhotoPorChaveNFe` | A consulta do primeiro timeout priorizado, 10009, retornou `ServerSOAPFaultException`, com indicação de autorização. A sequência foi interrompida. | Liberar a credencial Vedacit para a operação de consulta ou fornecer conferência oficial dos comprovantes. |

Os quatro timeouts de hoje continuam sendo 10009, 10056, 10583 e 10608. Os demais ativos são 9004, 9020, 9027, 9044, 9065, 9070, 9167, 9192, 9229, 9230, 9236, 9286, 9335, 9345, 9447, 9564, 10180, 10362 e 10511. Nenhum foi baixado como sucesso ou liberado para reenvio.

O HTTP 401 confirma a recusa atual, mas não distingue expiração, valor incorreto e falta de escopo. A recusa na consulta SOAP não demonstra falha da permissão de envio nem prova que o comprovante anterior deixou de chegar. O contrato da consulta por NF-e retorna arquivo/extensão, sem CT-e; quando o acesso for liberado, a presença de um arquivo pela NF-e ainda deverá ser correlacionada ao comprovante do CT-e afetado antes da baixa.

## Tratamento já preparado e próximo passo exato

O candidato existente conserva os erros de origem na auditoria, limita a recuperação e o cooldown, alterna XML/comprovantes e mantém os timeouts fora do reenvio automático. A verificação de acesso não alterou o pacote ou o runtime. Na entrega posterior autorizada pelo usuário, às 17:10, o mesmo pacote foi instalado no caminho operacional e a V22 foi aplicada; os dois processos permaneceram parados para início humano. A publicação está registrada em `rodizio-xml-comprovantes-2026-09-11.md` e não altera as dependências externas verificadas aqui.

1. Origem ESL/SFTP: liberar o download com a credencial configurada ou publicar os seis XMLs válidos. Em seguida, validar download e correlação antes da recuperação pelo candidato publicado.
2. Vedacit/MultiTMS: liberar `BuscarCanhotoPorChaveNFe` ou fornecer conferência oficial por NF-e/CT-e dos 23 registros. Somente então registrar confirmações ou autorizar a recuperação dos comprovantes comprovadamente ausentes, preservando a auditoria.

Não existe evidência suficiente para encerrar esses registros como resolvidos. As duas dependências são externas; as verificações locais e de acesso desta entrega estão concluídas.

## Evidências e validação

- SQL de leitura versionado e executado exclusivamente em `SATELITE_TMS_AUDITORIA`: `database/sql/diagnostico/20260911_autorizacao_xml_timeouts.sql`.
- Probe isolado Java 17 em `target/verificacao-autorizacao-20260911/`, sem Spring/scheduler, com consultas Feign/SOAP gerado/SFTP e documentos somente em memória. Resultados sanitizados em `resultado.txt` e `consulta-soap.txt`. Houve uma correção do caminho local do WSDL no probe antes da consulta SOAP efetiva; a tentativa anterior falhou localmente, sem contato com a Vedacit.
- Nenhuma alteração de credenciais, envio SOAP, escrita no banco/SFTP, reinício de serviço ou mensagem a fornecedores foi feita.
- Não houve alteração do código de aplicação nesta verificação; permanecem válidos os testes do candidato documentados no relatório de rodízio.
