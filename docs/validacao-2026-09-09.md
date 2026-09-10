# Revisão e validação — 09/09/2026

## Conclusão e alcance

A revisão reproduziu falhas no código e acrescentou testes de regressão. A suíte completa foi executada com medição JaCoCo, incluindo o contexto Spring isolado. A conferência externa se limitou a leitura SFTP, descrições públicas de serviços e GETs ESL. Nenhum documento foi enviado, reprocessado, excluído ou alterado remotamente. Nenhuma conexão SQL, migration, reinicialização de serviço ou alteração do JAR operacional foi realizada.

**Não é uma certificação de funcionamento integral em produção nem de 100% de cobertura.** O relatório HTML mostra também classes, linhas, métodos e decisões ainda não exercitados. Uma classe coberta significa que algum código dela foi executado pelos testes; isso não demonstra que seja utilizada no processo operacional atual.

## Evidências

Resultado final: **300 testes descobertos, 297 aprovados, zero falhas, zero erros e 3 manuais desabilitados**, em 50 classes de teste. Foram acrescentados 116 casos em relação à suíte inicial de 184. Compilação Java 17 e execução final concluíram com `BUILD SUCCESS`.

| Métrica do código próprio | Antes | Depois |
| --- | ---: | ---: |
| Classes exercitadas | 77,78% | 85,89% (140/163) |
| Métodos | 65,90% | 75,62% (763/1.009) |
| Linhas | 57,65% | 67,88% (3.717/5.476) |
| Condições/desvios | 42,47% | 53,54% (1.617/3.020) |

O denominador aumentou porque foram acrescentadas proteções no código. Restam 1.759 linhas e 1.403 desvios não exercitados. As maiores lacunas de linhas estão em `EtlRegistroService` (285), `EtlRepescagemService` (177), `VedacitIntegrationService` (124) e `IntegracaoAuditoriaQueryRepository` (84).

- Relatório do código próprio: [JaCoCo HTML](../target/unit-tests/coverage-20260909-193713-449/coverage-own/index.html).
- Relatório incluindo SOAP gerado: [JaCoCo completo](../target/unit-tests/coverage-20260909-193713-449/coverage-all/index.html).
- Inventário por classe: [classes.csv](../target/unit-tests/coverage-20260909-193713-449/classes.csv); [23 classes executáveis sem cobertura](../target/unit-tests/coverage-20260909-193713-449/classes-sem-cobertura.json).
- Resultados por teste: [Surefire](../target/unit-tests/coverage-20260909-193713-449/surefire-reports/).
- Resumo mensurável: [summary.json](../target/unit-tests/coverage-20260909-193713-449/summary.json).
- Log final: [tests-final.log](../target/unit-tests/coverage-20260909-193713-449/tests-final.log).
- Reprodução das falhas antes das correções: [tests-red.log](../target/unit-tests/coverage-20260909-192355-724/tests-red.log).
- Inventário e validação de amostra real: [sftp-validator-readonly.txt](../target/unit-tests/sftp-validator-readonly.txt).
- Contratos SOAP consultados e respostas ESL da primeira consulta: [summary.json](../target/unit-tests/remote-contracts/summary.json).

Os artefatos em `target/` são locais e ignorados pelo Git; este documento e o script de testes são versionáveis. O resumo não contém credenciais, nomes de arquivos fiscais ou dados pessoais.

## Falhas reproduzidas e corrigidas localmente

| Problema | Evidência anterior | Correção e teste |
| --- | --- | --- |
| Cursor ESL podia retroceder | Uma página com cursor solicitado `100` e retornado `90` gravava `90` no repositório mockado. | `EtlFluxoDestinoService` interrompe a regressão antes da persistência. `EtlFluxoDestinoSafetyTest` verifica ausência de gravação e de outra consulta. |
| Ordem de eventos SELIA usava criação do registro | Eventos ocorridos às 08h e 09h eram processados na ordem inversa se `created_at` estivesse invertido. | Ordenação SELIA por `occurrence_at`; a referência temporal do filtro retroativo permanece preservada. O teste também cobre item elegível posterior a um item cuja criação está fora da janela. |
| Ciclo SFTP relatava sucesso com erro de processamento | Resultado com `erros=1` produzia `CONCLUIDO` e código de saída `0`. | Auditoria recebe `FALHA` e saída `1`, mantendo conexão `OK` e as contagens do resultado. |
| Falha de auditoria era ocultada pelo código de saída | Exceção ao registrar o ciclo permitia saída `0`. | O ciclo sinaliza falha mesmo quando não consegue registrar sua auditoria. |
| Falha posterior à conexão era apresentada como falha SFTP | Inventário falho após conexão bem-sucedida era auditado com `conexao=FALHA`. | Preservação de `conexao=OK`. Detalhamento completo de etapas/motivos e métricas parciais permanece pendente da V22. |
| Leitura direta SFTP ignorava a janela de upload e o tipo do arquivo | Caminhos diretos tentavam abrir uploads recentes e links com nomes válidos; inventário aceitava arquivos não regulares. | Verificação de estabilidade em todos os caminhos, rejeição de links/arquivos não regulares, `lstat` dos diretórios e nova conferência antes/depois da leitura. |
| Download SFTP podia crescer além do tamanho anunciado | Leitor continuava consumindo dados adicionais até EOF. | Interrupção ao ultrapassar o tamanho anunciado ou configurado, sempre abrindo com `OpenMode.READ`. |
| XML era correlacionado pela presença textual das chaves | Um XML contendo as duas chaves apenas em comentário era aceito. | `CteXmlValidator` verifica `infCte/@Id` e NF-e referenciada no namespace fiscal. DTD/entidades externas são bloqueadas. A amostra XML real do SFTP também foi aceita pelo novo validador. |

Nenhum código gerado SOAP foi editado manualmente. Endpoints, credenciais, DePara, habilitações operacionais e política de reenvio de documentos não foram alterados.

## Conferência de regras por fonte

| Fonte/destino | O que foi conferido | Limite da evidência |
| --- | --- | --- |
| SFTP Vedacit | 3.085 arquivos em comprovantes: 3.048 com nomes reconhecidos, correspondentes a 2.969 NF-es distintas; 37 nomes fora do padrão. Formatos: 2.163 JPG, 465 JPEG, 82 JFIF, 342 PDF e 33 PNG. Pasta XML com 1.488 arquivos. Nenhum link, tamanho inelegível ou upload recente observado no inventário. | Fotografia do momento; não garante conteúdo correto em todos os arquivos ou contrato futuro de nomes/retenção. Os 37 nomes não motivaram ampliação automática da regra de aceitação. |
| Correlação SFTP | Uma amostra XML de 9.001 bytes possui uma identidade CT-e e uma NF-e referenciada; foi encontrado comprovante com o mesmo par. Validador novo aceitou a amostra; ancestrais da pasta são diretórios regulares. | Apenas uma amostra de conteúdo, lida em memória. Os documentos não foram persistidos nem enviados. |
| Vedacit SOAP | GET dos WSDLs atuais de CT-e, NF-e e ocorrências retornou HTTP 200; existem as operações documentais usadas pelo código. | Disponibilidade da descrição não valida autenticação, aceitação de documentos ou efeitos de uma chamada SOAP. |
| WSDL de ocorrências local | O contrato publicado possui `IntegracaoDocumentoComplementar`, ausente no WSDL local. | Confirma defasagem do snapshot local; não justifica editar proxies gerados manualmente. Atualização coerente WSDL/XSD/geração continua pendente. |
| PPG | O manual fornecido pelo cliente confirma os endpoints PHP usados por `PpgClient`, token de 14 dias e imagem JPEG 1536×240, 150 dpi, com prefixo MIME. Os testes verificam cache do token, concorrência, payload, duplicidade e propriedades binárias da imagem. | O blueprint interno contém endpoints diferentes e deve ser tratado como histórico. Não houve login/envio remoto PPG; a tentativa de obter Swagger público retornou 404. |
| SELIA/Intelipost | A documentação pública atual confirma AddEvents, identificação do volume, timestamp com fuso e importância da cronologia. Os testes existentes/adicionados cobrem PLP, autenticação, limites, correlação pedido/volume, POD e eventos. | DePara e aceite específicos do cliente não podem ser deduzidos apenas do esquema público. Não houve POST AddEvents. |
| SUPPORTE | Manual fornecido pelo cliente conferido contra os testes: Basic Auth, NF-e/CT-e, datas `dd-MM-yyyy HH:mm:ss`, MIME no comprovante e tratamento da resposta de negócio. | Manual é evidência fornecida, não comprovação de contrato remoto atualizado. Swagger público consultado retornou 404; não houve POST ou autenticação externa. |
| ESL | Seis GETs limitados a uma página, nos escopos configurados, retornaram 500. Duas consultas dirigidas pela whitelist, sem `since`, retornaram 500 e 429; as consultas foram encerradas. | Não foi possível confirmar o conteúdo atual dos eventos 1/110/114. Os códigos existentes não foram alterados por suposição. Houve sete respostas 500 e uma 429, sem retry automático. |

O contrato publicado de ocorrências foi comparado com o snapshot local por nome das operações. Não foi feita validação exaustiva de todas as estruturas XSD. As referências atuais são os WSDLs oficiais de [CT-e](https://vedacit.multiembarcador.com.br/SGT.WebService/CTe.svc?wsdl), [NF-e](https://vedacit.multiembarcador.com.br/SGT.WebService/NFe.svc?wsdl) e [ocorrências](https://vedacit.multiembarcador.com.br/SGT.WebService/Ocorrencias.svc?wsdl).

A cronologia e a correspondência dos identificadores da SELIA foram conferidas na documentação oficial de [Adicionar Evento de Rastreamento](https://docs.intelipost.com.br/docs/tms-embarcador/3af327cbea04a-adicionar-evento-de-rastreamento). A descrição pública não substitui a homologação dos códigos do cliente.

O SFTP Intelipost da captura anterior é uma integração separada. Esta revisão autenticada usou exclusivamente o perfil Vedacit configurado; não acessou outros clientes nem inferiu layout OCOREN/NOTFIS a partir de arquivos processados.

## Cobertura e segurança da execução

O JaCoCo mede todo o código próprio, incluindo DTOs, modelos, controllers e runners. Proxies/classes SOAP gerados ficam separados por namespace para não distorcer a métrica do código mantido pela equipe. Não foram criados testes de getters apenas para inflar porcentagens.

O script [testar_com_cobertura.ps1](../scripts/testar_com_cobertura.ps1) usa Java 17 e Maven offline, copia as fontes SOAP geradas sem edição e compila em saída própria dentro de `target/unit-tests`. Goals diretos evitam a limpeza e o download de WSDL do ciclo Maven padrão. A JVM de teste bloqueia conexões/listeners, usa diretório de trabalho isolado, não importa `.env` e desabilita os fluxos operacionais. Chamadas REST, SOAP e JDBC são mockadas nos testes; as sondagens externas descritas acima foram processos separados e somente de leitura.

Os três testes manuais desabilitados são `ExtracaoRealPpgDryRunTest`, `ProcessadorImagemTest` e `VedacitImageCompressionVisualTest`. A suíte testa processamento de imagens com massa sintética segura em outros testes. Runners que usam `System.exit` não são iniciados operacionalmente; a lógica de rodadas é chamada isoladamente com dependências mockadas.

Exemplo reproduzível neste workspace:

```powershell
& scripts/testar_com_cobertura.ps1 -Maven 'C:/Program Files (x86)/apache-maven-3.9.14/bin/mvn.cmd' -JavaHome '.tools/temurin17/jdk-17.0.19+10'
```

O script falha se faltarem dependências locais, se os testes falharem, se o bloqueio de rede não for comprovado ou se o hash do JAR operacional mudar. A medida não demonstra ausência de erros nos trechos não executados, nem uso de todas as classes em produção.

## Validações ainda necessárias

1. Teste SQL dirigido em `SATELITE_TMS_AUDITORIA` com inventário acima de 2.100 NF-es: execução das consultas particionadas, ordenação, deduplicação, locks e saldo no mesmo snapshot. Os testes atuais verificam essas decisões com mocks; não executam o SQL Server.
2. Atualização coerente do snapshot WSDL/XSD de ocorrências e regeneração automática do cliente SOAP, seguida de criação de proxy offline e validação do artefato. O teste de fallback atual cobre a decisão com mocks.
3. Homologação operacional dos envios SOAP/REST com massa real e autorização específica, incluindo timeout ambíguo, aceitação, duplicidade e confirmação no destino. Esta revisão não executou envios.
4. Nova leitura ESL após normalização do serviço/limite, para validar dados atuais dos eventos. Não substituir a observação remota por documentação histórica.
5. Aprofundar os testes nos caminhos ainda descobertos de `EtlRegistroService`, `EtlRepescagemService`, integrações e runners legados, conforme o relatório por classe. O inventário de lacunas não é autorização para remover classes.
6. Permanecem as pendências já registradas: consultas N+1 na materialização SFTP, V22 para detalhes e contagens parciais de falha, sanitização de logs e homologações específicas dos clientes.

JAR operacional preservado: SHA-256 `A77DB9AD8428E5B47553955D0534EB3F83B7511B5E9404A2EB17BE8EEB937751`.
