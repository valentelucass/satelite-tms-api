# Validação do pacote candidato — 09/09/2026

**Atualização às 23:31 BRT:** após a parada manual dos dois processos do Satélite, o pacote foi recompilado e colocado no caminho padrão do PM2, com backup anterior preservado. Os 1.458 arquivos internos coincidem com o candidato validado. A retomada dos processos permanece a cargo do operador; detalhes no registro ao final.

**Resultado: validação técnica aprovada; envio real ainda não confirmado.** O candidato foi gerado em saída isolada, com Java 17 e clientes SOAP recém-gerados dos contratos oficiais. A única pendência operacional encontrada na prévia é um documento sem comprovante correspondente disponível no SFTP. Não há massa elegível para completar agora o ciclo supervisionado.

## Evidências

| Verificação | Resultado |
| --- | --- |
| Geração automática dos clientes SOAP | CT-e, NF-e e Ocorrências: `BUILD SUCCESS`, usando as URLs oficiais do POM. |
| Compilação e empacotamento | Java 17; JAR de 79.355.326 bytes em saída isolada; artefato operacional preservado. |
| Suíte do candidato | 303 testes em 51 classes: 300 aprovados, zero falhas/erros e três manuais ignorados. JVM de testes com conexões/listeners bloqueados. |
| Correspondência do pacote | 1.243 arquivos de aplicação dentro do JAR coincidem byte a byte com a saída compilada/testada, incluindo recursos locais. Sem `.env` nem classes de teste no pacote. |
| Proxies usando o próprio JAR | CT-e, NF-e e Ocorrências criados com sucesso usando classes, bibliotecas e recursos empacotados; rede bloqueada. A aplicação Spring não foi iniciada. |
| SQL Server real | Exclusivamente `SATELITE_TMS_AUDITORIA`: 2.979 NF-es auditadas distintas, seis blocos por fila e máximo de 502 parâmetros por statement. |
| Seleção e saldo SQL | Fila normal: 1; técnica: 0; saldo: 1. Resultado, ordenação, deduplicação e contagem coincidiram com SQL independente na mesma transação; duplicação das chaves de entrada não alterou a contagem. |
| SFTP com o cliente novo | 3.058 arquivos válidos e 37 rejeitados. Um candidato SQL, zero com metadado/comprovante correspondente disponível; nenhum pronto para envio. |

O teste SQL usa o JPQL das anotações reais de `LogIntegracaoRepository`, Hibernate e os métodos reais de particionamento/seleção de `EtlRepescagemService`. A comparação independente utiliza SQL com `ROW_NUMBER` e contagem distinta. Foi usada transação serializável curta, com timeout de lock de 1.500 ms, timeout de consulta e rollback ao encerrar. O guard JDBC bloqueia escrita, DDL e commit. Foram contabilizadas 30 execuções explícitas, incluindo `SET LOCK_TIMEOUT`; não houve escrita. A massa real atual tem apenas um candidato, portanto a ordenação com vários candidatos é complementada pelos testes unitários existentes.

A consulta dirigida está em [SqlSftpReadOnlyProbe.java](../scripts/validacao/SqlSftpReadOnlyProbe.java), fora dos fontes de produção. Foi compilada com o classpath do candidato e executada separadamente, recebendo o caminho do `.env` e um arquivo de configuração de log silencioso. Ela valida o nome da base tanto na URL quanto no catálogo conectado, sem expor credenciais, chaves fiscais ou linhas de negócio nos resultados. Não inicializa a aplicação nem chama integrações.

## Correção encontrada durante a validação

A criação real do proxy de Ocorrências falhou inicialmente: o snapshot local não continha `IntegracaoDocumentoComplementar`, presente no cliente recém-gerado. O contrato foi atualizado por GET a partir do [WSDL oficial de Ocorrências](https://vedacit.multiembarcador.com.br/SGT.WebService/Ocorrencias.svc?wsdl), percorrendo suas dependências e convertendo os imports para caminhos locais. Foram conferidos 28 arquivos; sete tinham diferenças semânticas e foram atualizados: `Ocorrencias.wsdl`, `xsd0.xsd`, `xsd4.xsd`, `xsd5.xsd`, `xsd7.xsd`, `xsd10.xsd` e `xsd24.xsd`.

O teste `VedacitSoapContractTest` agora cria os três proxies reais com os recursos locais. Ele passou na suíte e a criação foi repetida usando o próprio Fat JAR, ainda com rede bloqueada. Nenhuma classe/DTO SOAP foi editada manualmente; a geração ocorreu pelo `jaxws-maven-plugin`.

Os recursos SOAP eram ignorados pelo Git. O `.gitignore` foi ajustado para incluir somente os snapshots WSDL/XSD dos três serviços Vedacit, necessários ao pacote, mantendo outros dumps locais ignorados. O conjunto inclui três WSDLs e 80 XSDs; os demais contratos locais foram preservados e passaram na criação dos proxies. O helper SQL também foi explicitamente incluído no conjunto para commit. Arquivos gerados, artefatos, credenciais e resultados locais continuam ignorados.

## Artefato e reprodução

- Candidato: `target/release-validation/20260909-225338/satelite-0.0.1-SNAPSHOT.jar`.
- SHA-256 do candidato: `A136ADA9C7E2FFDD3197A306CE26BC9E213388D476D3CC657399D6A054EA115D`.
- JAR operacional preservado: `target/satelite-0.0.1-SNAPSHOT.jar`.
- SHA-256 operacional: `A77DB9AD8428E5B47553955D0534EB3F83B7511B5E9404A2EB17BE8EEB937751`.
- Ponteiro da execução: `target/release-validation/latest.txt`.

Na pasta do candidato estão `summary.json`, `wsimport.log`, `compile.log`, `package.log`, `tests.log`, `surefire-reports/`, `source-resource-hashes.json`, `sql-readonly.json`, `soap-offline-before.json`, `soap-packaged-offline.json`, `sftp-candidate-preflight.json`, `jar-inspection.json`, `jar-tested-files.json` e `coverage-own/index.html`. Esses arquivos são evidências locais e não entram no commit. O `test-pom.xml` e o guard de rede usados nesta execução também estão nessa pasta. O ponteiro anterior de `target/unit-tests` continua identificando a suíte histórica, anterior aos três testes de contrato.

A geração usou os três goals `wsimport` identificados no POM; a compilação e o empacotamento usaram goals diretos com `-Dsatelite.build.directory` apontando para a saída isolada, evitando a limpeza do diretório operacional. A geração consultou os serviços oficiais; as dependências Maven vieram do cache local. Isso não prova disponibilidade das dependências em outra máquina ou build sem acesso aos WSDLs oficiais.

## Limites e próxima etapa

A cobertura do código próprio continua em 67,88% das linhas e 53,54% dos desvios, com 85,89% das classes exercitadas. Não representa 100% de cobertura nem demonstra que todas as classes sejam usadas pelo worker produtivo. Os três testes ignorados são manuais de extração/imagem; não foram contados como aprovados.

Criar o proxy confirma compatibilidade do contrato empacotado, mas não autenticação, resposta SOAP ou aceite de negócio remoto. O ciclo do worker operacional já existente confirmou a mesma ausência de comprovante, com zero envios e sem consulta ESL; ele executa o JAR anterior, portanto não substitui o ciclo do candidato.

Quando houver comprovante elegível, resta acompanhar um ciclo do candidato com limite explícito de dez NF-es, sob condução humana do runtime, conferindo locks, resposta SOAP, auditoria e saldo. Sucessos anteriores, bloqueios e timeouts ambíguos permanecem fora da massa de teste. SELIA/SUPPORTE e os demais caminhos de negócio mantêm suas pendências próprias de homologação.

Nesta validação houve apenas leitura remota dos contratos oficiais, SQL da base permitida e SFTP Vedacit. Não houve novas consultas ESL, POST SOAP/REST, escrita SQL/SFTP, alteração de configuração, staging, commit, implantação nem start/restart de serviços.

## Recompilação e disponibilização autorizada às 23:31 BRT

O operador parou manualmente `WORK-SFTP-CLIENTES` e, após a identificação do bloqueio do arquivo pelo Windows, também `Satelite-API-19090`. O PM2 confirmou ambos em `stopped`, PID zero, e o JAR ficou disponível para acesso exclusivo.

A compilação dos 1.105 fontes e o empacotamento foram executados novamente com Java 17 em `target/release-build/20260909-232906`, sem iniciar serviços e sem executar limpeza no diretório operacional. Foram reutilizados sem edição os 971 fontes SOAP gerados dos WSDLs oficiais na validação imediatamente anterior. O Maven concluiu com `BUILD SUCCESS`. A conferência por SHA-256 de cada entrada do JAR retornou **1.458 arquivos idênticos e nenhuma diferença**, incluindo bibliotecas e launcher. Os fontes também permanecem idênticos; a suíte já aprovada não foi repetida. O SHA-256 do arquivo ZIP/JAR mudou com os metadados de empacotamento, embora os conteúdos internos sejam iguais.

O novo arquivo de 79.355.326 bytes foi copiado para uma área de preparação, conferido e substituiu atomicamente o JAR padrão, preservando o anterior. Os dois hashes foram conferidos após a troca:

| Artefato | Caminho | SHA-256 |
| --- | --- | --- |
| JAR disponível para o PM2 | `target/satelite-0.0.1-SNAPSHOT.jar` | `EBC700C71B264A50EC933FDD290B3761DBB915152420AE5D6626746ADB7F60E2` |
| Backup anterior | `target/release-build/20260909-232906/satelite-anterior-A77DB9AD.jar` | `A77DB9AD8428E5B47553955D0534EB3F83B7511B5E9404A2EB17BE8EEB937751` |

As evidências desta etapa estão em `build.log`, `content-comparison.json` e `installation.json`, na pasta de recompilação. Nenhum start/restart foi executado pela automação, nem houve escrita SQL, acesso a integrações ou mudança de configuração. O operador retomará os dois processos; a confirmação do envio real permanece condicionada a comprovante elegível.
