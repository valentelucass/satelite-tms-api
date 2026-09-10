# Avaliação das alterações pendentes — 09/09/2026

**Parecer atualizado: favorável ao commit e à validação técnica do pacote; falta confirmar um envio real do candidato.** Após autorização, o novo JAR, as consultas SQL reais e os três proxies SOAP passaram. A suíte atual teve 300 aprovados, zero falhas/erros e três manuais ignorados. A prévia SFTP não encontrou comprovante correspondente para o único candidato disponível, impedindo concluir o ciclo de envio. Resultado completo em [validacao-pacote-2026-09-09.md](validacao-pacote-2026-09-09.md).

O registro abaixo preserva a revisão inicial, anterior a essa validação. Os passos 1 e 2 foram concluídos; os recursos SOAP passaram a integrar o conjunto para commit e o passo 3 continua pendente. Não houve implantação nem mudança de runtime.

## Escopo e evidências conferidas

No início da revisão havia 34 arquivos pendentes: 18 modificados e 16 novos, sem arquivos no staging. Foram conferidos o diff de produção, os testes de regressão associados, o script de cobertura, os resultados existentes e o artefato operacional. Não havia mudanças em `.env`, scripts de database ou recursos/configuração de produção.

Os **184 arquivos Java atuais** correspondem exatamente, por SHA-256, ao inventário da execução `coverage-20260909-193713-449`: nenhum arquivo alterado, ausente ou adicional fora desse inventário. A execução teve **300 testes descobertos, 297 aprovados, zero falhas/erros e três testes manuais desabilitados**, com `BUILD SUCCESS`. Os resultados foram revalidados por correspondência dos arquivos; a suíte não foi repetida nesta revisão porque os fontes permanecem idênticos.

O `git diff --check`, a leitura XML do POM e a análise sintática PowerShell do script de cobertura passaram. A cobertura própria permanece em 67,88% das linhas e 53,54% dos desvios. Esses percentuais e os mocks não substituem validação do SQL Server, dos contratos remotos ou do JAR a implantar.

## Avaliação por mudança

| Mudança | Benefício verificado | Limite para publicação |
| --- | --- | --- |
| Consultas SFTP em blocos, filtros de registros ativos, contagem e limites por perfil | Evita ultrapassar o limite de parâmetros, preserva ordenação e restringe reprocessamentos elegíveis. | O JAR operacional já contém métodos de particionamento e limite por perfil; a validação SQL dirigida de fronteiras/contagem continua distinta dos testes mockados. |
| Proteção contra regressão do cursor ESL | Impede gravar cursor menor que o solicitado. | O comportamento foi reproduzido e testado localmente; os fluxos gerais ESL não foram executados remotamente com esta alteração. |
| Ordenação de eventos SELIA por ocorrência | Corrige a ordem dentro da página e preserva itens elegíveis da mesma página no retroativo. | Não habilitar a integração SELIA com base apenas nesses testes; homologação cronológica entre páginas e aceitação pelo cliente continuam necessárias. |
| Auditoria e saída do worker | Erros de processamento/auditoria passam a sinalizar falha; erro posterior à conexão não é rotulado automaticamente como falha de conexão. | Verificar as contagens e o comportamento do PM2 no primeiro ciclo do novo artefato. |
| Proteções de leitura SFTP e correlação XML | Rejeita links/tipos indevidos, uploads instáveis, tamanho alterado e chaves presentes apenas como texto sem correlação documental. | A nova classe `CteXmlValidator` não existe no JAR operacional; deve entrar no commit e no pacote. A amostra SFTP já validada não representa todos os documentos. |
| Testes, cobertura, documentação e saída Maven isolada | Aumenta a capacidade de reproduzir falhas e evita sobrescrever o JAR usado em produção durante os testes. | O script de cobertura depende de Java 17, cache Maven e fontes SOAP previamente geradas. Não é prova de build completo a partir de checkout limpo. |

Nenhuma mudança observada no diff remove fallback ESL, altera endpoints/DePara, ativa clientes ou muda credenciais. O diagnóstico de consumo ESL e a correção incremental de usuários pertencem a escopos diferentes: a correção do extrator foi registrada no outro projeto em `a602b75`; ela não está sendo implantada por este conjunto do Satélite.

## O que falta para considerar a implantação aprovada

1. Gerar o novo JAR em saída isolada e conferir seu conteúdo, contratos SOAP gerados/empacotados e configuração efetiva. O JAR operacional preservado não representa todo o conjunto de fontes agora testado. O WSDL local de ocorrências tem uma defasagem já conhecida, descrita no relatório de validação; a geração/pacote deve ser conferida sem editar proxies manualmente.
2. Validar a seleção/contagem SQL dirigida somente em `SATELITE_TMS_AUDITORIA`, preservando dados e comparando a fronteira com mais de 2.100 NF-es. O sucesso operacional já observado é evidência do caminho usado, não de todas as fronteiras.
3. Após validar o artefato, acompanhar um ciclo Vedacit com no máximo dez itens, configuração atual e conferência da auditoria e resposta do destino. Sucesso significa confirmação coerente do cliente e dos estados locais, sem reenvio de timeout ambíguo ou registros bloqueados. A publicação é uma etapa posterior, não executada nesta revisão.

Essas condições são para liberar o pacote operacional com evidência. O commit pode preservar agora as correções e testes, incluindo os arquivos novos; usar apenas arquivos já rastreados deixaria de fora `CteXmlValidator` e parte da cobertura acrescentada.

Não foi feito commit, staging, build do JAR operacional, reinício de serviço, chamada remota, escrita SQL ou mudança de configuração nesta revisão. O JAR permanece com SHA-256 `A77DB9AD8428E5B47553955D0534EB3F83B7511B5E9404A2EB17BE8EEB937751`.

Evidências complementares: `target/unit-tests/revisao-pre-commit-classes-20260909.json`, `target/unit-tests/coverage-20260909-193713-449/source-hashes.json`, `summary.json` e `tests-final.log`; contexto de homologação em [validacao-2026-09-09.md](validacao-2026-09-09.md).
