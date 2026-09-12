# Satélite TMS — instruções do repositório

ETL Java 17/Spring Boot: lê ocorrências e documentos ESL/Rodogarcia e integra PPG, Vedacit, SELIA e SUPPORTE conforme os perfis habilitados. O processamento principal usa scheduler/runners; a API auxiliar atende auditoria, quarentena e contratos específicos existentes.

## Contexto conforme a tarefa

- Consulte o estado recente e as pendências relacionadas em [states.md](states.md) ao retomar trabalho ou alterar comportamento, configuração ou operação. Diferencie histórico de estado atual; uma correção textual não exige releitura integral.
- Consulte [CONTEXTO_GLOBAL.md](../etl-dash/CONTEXTO_GLOBAL.md) para fronteiras arquiteturais, banco, publicação, processos produtivos ou trabalho entre repositórios. O banco permitido ao Satélite continua exclusivamente `SATELITE_TMS_AUDITORIA`.
- Use os scripts pertinentes em `database/sql/` para SQL, o relatório relacionado em `docs/` para incidentes e o cliente/DTO ou WSDL correspondente para contratos.
- Instruções explícitas do usuário prevalecem sobre orientações locais. Anotações antigas não revogam autorizações já dadas para a mesma ação e escopo.

## Autonomia e conclusão

- Prossiga com investigação, edição local e validação pertinente sem pedir aprovação por etapa. Procure nas fontes disponíveis e já autorizadas antes de pedir informações ao usuário. Resolva escolhas rotineiras; pergunte quando faltar uma decisão material.
- Conclua o trabalho autorizado e corrija falhas decorrentes da alteração. Auditorias precisam explicar causas e números com evidências; correções precisam de validação. Distinga código pronto, pacote gerado e versão em execução.
- Instalação operacional, controle de processos produtivos, habilitação de integrações e envios/reenvios precisam estar abrangidos pela autorização operacional do usuário. Considere autorizações existentes e o contexto global. Se faltar aprovação, prepare o resultado revisável antes de perguntar e explique a ação e a regra aplicável. Teste local não autoriza transmissão real.
- Comunique achados e resultados em português simples, com atualizações curtas. Explique quando solicitado, sem recitar instruções nem impor silêncio absoluto. Preserve alterações fora do escopo; diante de bloqueio externo, registre a evidência e continue o trabalho independente.

## Arquitetura e contratos

- Separe `clients` (OpenFeign REST), `dto.*` (contratos), `services.*` (regras por domínio), `repositories` (acesso à auditoria), `models` (entidades de auditoria/estado técnico) e `utils` (lógica pura). Não use `RestTemplate` ou `WebClient`.
- DTOs próprios usam `record`, `camelCase`, `@JsonProperty` para nomes externos e `@JsonIgnoreProperties(ignoreUnknown = true)` nos DTOs raiz. Credenciais, URLs e IDs de configuração vêm de propriedades/`.env`, injetados pela configuração Spring. Não exponha segredos em código, logs ou relatórios.
- Preserve o fluxo principal em scheduler/runners; não crie `@RestController` para dispará-lo. A API auxiliar permanece separada.
- Entrega/comprovante usa `occurrence.code == 1`; XML Vedacit tem fluxo próprio para `110`. Outros eventos dependem dos mapeamentos e fluxos habilitados do destino. Consulte as regras consolidadas em `states.md` ao alterar filtros ou DePara.
- **PPG:** preserve o cache em memória do token LoopBack de 14 dias e imagem JPEG, fundo branco sem alfa, 1536x240 pixels e prefixo `data:image/jpeg;base64,`.
- **Vedacit:** contratos SOAP são gerados pelo `jaxws-maven-plugin` a partir dos WSDLs oficiais, nunca escritos/editados manualmente. Saídas: `target/generated-sources/wsimport*`; contratos empacotados: `src/main/resources/wsdl/vedacit`.
- Isole falhas por documento e registre o resultado sem derrubar o lote. Preserve correlação exata NF-e/CT-e, locks, aceites e primeiras datas. Timeout, ausência de data ou arquivo encontrado não autorizam inventar confirmação ou reenviar aceites. Respeite o modo SFTP exclusivo quando habilitado.

## Banco e rastreabilidade

- Toda consulta, script, teste ou automação SQL Server deste projeto usa apenas `SATELITE_TMS_AUDITORIA` (`satelite_tms_auditoria`). Não acesse `ETL_SISTEMA` nem outra database por este repositório.
- Persista somente auditoria, cursores, quarentena e estado técnico; não armazene arquivos NF/XML, imagens ou dados de domínio no banco.
- Preserve logs e estados por exclusão lógica (`arquivado`, `ativo`, `deleted_at` ou equivalente). Não os remova com `DELETE FROM` ou `TRUNCATE`. Leituras operacionais filtram inativos quando aplicável.
- Mudanças de banco exigem `.sql` versionado e idempotente. Após `USE`, inclua `SET ANSI_NULLS ON;` e `SET QUOTED_IDENTIFIER ON;`. Disponibilize a atualização por `database/subir_database.bat`, preferindo o modo restrito pertinente para evitar reaplicar saneamentos históricos.

## Validação e estado

- Para Java, use [scripts/testar_com_cobertura.ps1](scripts/testar_com_cobertura.ps1) com JDK 17 e `-Testes` para as classes afetadas. O script usa saída isolada, fontes SOAP locais e bloqueio de rede na JVM de testes. Execute/corrija sem aprovação entre rodadas; amplie os testes quando risco ou falha justificar.
- Para documentação, revise diff, links e consistência; não execute suíte Java, banco ou serviços. Runners operacionais e testes externos são fluxos distintos da suíte isolada.
- Prepare builds com `satelite.build.directory` separado do JAR operacional. Confira o conteúdo testado antes de publicar; build isolado não significa instalação concluída.
- Ao mudar comportamento, decisões, instruções ou andamento, atualize somente as seções pertinentes de `states.md`: retire pendências concluídas, preserve histórico útil e registre evidências, limites e versão ativa/candidata. Não reescreva o arquivo inteiro nem crie pendências para simples correções textuais.
