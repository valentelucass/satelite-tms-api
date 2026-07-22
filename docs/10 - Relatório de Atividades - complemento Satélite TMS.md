## 4.1. Linguagens de Programação Utilizadas

O núcleo do Sistema Satélite TMS foi desenvolvido em **Java 17 LTS**, linguagem utilizada para implementar o robô de integração, as regras de negócio, os clientes de API, os serviços SOAP, o agendamento automático, a auditoria e os endpoints auxiliares de consulta.

Para a persistência e as consultas analíticas de auditoria, foi utilizado **SQL/T-SQL** em Microsoft SQL Server. Essa camada mantém os logs de processamento, os cursores de paginação, os estados das integrações, as tentativas de reprocessamento e os dados consolidados usados no monitoramento operacional.

No módulo de dashboards que consome a API de auditoria, são utilizados **TypeScript** e **React** no front-end. Esse módulo apresenta as métricas, tabelas e gráficos da página “Integrações”, sem acessar diretamente a base do robô.

## 4.2. Frameworks e Bibliotecas

O projeto utiliza **Spring Boot 4.1.0** como framework principal para configuração, injeção de dependências, execução e organização da aplicação Java.

- **Spring Cloud OpenFeign:** consumo declarativo das APIs REST da Rodogarcia/ESL Cloud e da PPG/OK Entrega.
- **Spring Scheduling:** execução automática e periódica do ciclo ETL, sem necessidade de acionamento manual em tela.
- **Spring Web MVC:** disponibilização dos endpoints auxiliares de auditoria, consulta de canhotos e quarentena.
- **Spring Data JPA e Hibernate:** persistência dos logs técnicos e cursores no SQL Server.
- **Microsoft JDBC Driver:** conexão com o Microsoft SQL Server.
- **Jakarta XML Web Services, JAX-WS RI e jaxws-maven-plugin:** geração automática, a partir dos WSDLs oficiais, dos clientes SOAP da Vedacit/MultiTMS.
- **Jackson:** leitura e escrita dos contratos JSON trocados com as APIs REST.
- **Apache PDFBox:** renderização da primeira página de comprovantes recebidos em PDF.
- **Java ImageIO e AWT:** conversão, recorte, redimensionamento, compressão e codificação Base64 de imagens de canhoto.
- **Lombok:** redução de código repetitivo em modelos internos.
- **Maven Wrapper e testes do Spring Boot:** compilação reprodutível e testes automatizados de integração, ETL, auditoria e tratamento de imagens.

No dashboard integrado, são empregados **React**, **TypeScript**, **Axios** para chamadas HTTP, **TanStack React Query** para cache, atualização e estado das consultas, e **Apache ECharts** para os gráficos de acompanhamento operacional.

## 4.3. Plataformas de Apoio

O desenvolvimento e a operação da solução utilizam as seguintes plataformas de apoio:

- **Microsoft Windows:** ambiente operacional do serviço, com scripts para compilação, inicialização, parada, consulta de logs, carga retroativa e testes de ponta a ponta.
- **Apache Maven / Maven Wrapper:** gerenciamento de dependências, compilação, geração dos clientes SOAP e empacotamento da aplicação Java.
- **Microsoft SQL Server:** banco exclusivo `SATELITE_TMS_AUDITORIA`, destinado à auditoria, rastreabilidade, quarentena e controle de cursor.
- **Git e GitHub:** versionamento do código-fonte, histórico de alterações e colaboração técnica.
- **Rodogarcia / ESL Cloud:** plataforma de origem das ocorrências, CT-es e comprovantes de entrega.
- **PPG / OK Entrega:** plataforma REST de destino para a baixa de entregas e envio do canhoto no formato exigido.
- **Vedacit / MultiTMS:** plataforma SOAP de destino para ocorrências, XML de CT-e e digitalização de canhotos.
- **Portal de Dashboards:** plataforma web que consome a API de auditoria e apresenta a página “Integrações” para acompanhamento gerencial e operacional.

## 4.4. Arquitetura / Fluxo da Solução

O Satélite TMS possui arquitetura de **middleware ETL** (*Extract, Transform and Load*) e funciona de forma automática e headless, ou seja, sem uma tela necessária para o processamento principal. O ciclo é acionado por agendamento interno e atende os destinos PPG e Vedacit de forma independente.

O fluxo começa na extração das ocorrências da Rodogarcia/ESL Cloud. A aplicação consulta os eventos de forma paginada, com autenticação por *Bearer Token* e cursor de continuidade específico por destino. Somente ocorrências com código `1`, correspondente a “Entrega Realizada”, seguem para a transformação e envio. Registros com outros códigos são mantidos na auditoria como ignorados, sem gerar baixa indevida.

Na transformação, o sistema valida a NF-e, o frete, o CT-e e os dados necessários para cada integração. Quando há comprovante disponível, ele é baixado e adaptado ao padrão do destino. Para PPG, a imagem é convertida para JPEG RGB, sem transparência, com fundo branco, recorte configurável, dimensão final de **1536 x 240 pixels**, resolução de **150 DPI** e Base64 com prefixo MIME. Para Vedacit, o sistema aceita imagem ou PDF; no caso de PDF, renderiza a primeira página, converte para JPEG, limita o maior lado a 1024 pixels, comprime o arquivo para no máximo 400 KB e envia Base64 sem prefixo MIME.

Na carga, a PPG recebe um payload REST em JSON e a Vedacit recebe mensagens SOAP. O envio de Vedacit separa as etapas de ocorrência/dados, XML de CT-e — quando habilitado — e canhoto. Portanto, se o comprovante ainda não existir, os dados já aceitos não precisam ser reenviados: somente a pendência de imagem é retomada no próximo ciclo.

Após cada tentativa, a aplicação grava no SQL Server o status, o destino, a ocorrência, a NF-e, o frete, o cursor, as tentativas, mensagens de erro, datas e referência do canhoto. A persistência é técnica e de auditoria: imagens, notas fiscais completas e massa operacional não são copiadas desnecessariamente para o banco.

```text
Rodogarcia / ESL Cloud
  ocorrências, CT-e e comprovantes
             |
             | REST + autenticação Bearer
             v
Satélite TMS — Java / Spring Boot
  extração -> validação -> transformação -> auditoria
       |                    |                    |
       | REST JSON          | SOAP XML           | SQL Server
       v                    v                    v
PPG / OK Entrega      Vedacit / MultiTMS   SATELITE_TMS_AUDITORIA
                                                |
                                                | API de auditoria
                                                v
                                      Portal de Dashboards — página “Integrações”
```

Como mecanismos de confiabilidade, cada registro é tratado individualmente para que uma falha não interrompa o lote inteiro. A aplicação controla intervalo entre requisições, retenta falhas transitórias como *timeouts*, HTTP 429 e HTTP 5xx, aplica espera entre tentativas, identifica sequência de falhas de infraestrutura e mantém quarentena para itens que precisam de ação manual. Também há idempotência para impedir o reenvio automático de registros já finalizados e conciliação de respostas de duplicidade como sucesso operacional.

O braço de auditoria disponibiliza dados para o portal de dashboards por API. O navegador do usuário não acessa o banco do Satélite; ele consulta a API do portal autenticado, que valida a permissão de integração e encaminha a solicitação à API de auditoria do Satélite. Assim, há separação entre interface, autenticação, análise e processamento de dados.

Na página “Integrações”, os dados retornados alimentam os indicadores de volume operacional, sucesso global de dados/XML, sucesso de canhotos e pendências. A tela exibe gráfico de sazonalidade diária de sucessos e falhas, comparação de saúde entre PPG e Vedacit e resumo por etapa — XML/Dados e Canhoto. Também oferece abas de pendências, integrados com sucesso e quarentena, tabela paginada e filtrável, ordenação, consulta de mensagens de erro e visualização de comprovantes em imagem ou PDF. O front-end mantém cache de um minuto e atualiza as consultas periodicamente, além de permitir atualização manual.

## 4.5. Integrações Envolvidas

**1. Rodogarcia / ESL Cloud — origem REST:** a aplicação utiliza o endpoint de ocorrências `/api/customer/invoice_occurrences` com autenticação `Bearer`. A consulta aceita cursor, chave de NF-e, período e código de ocorrência. Também são consumidos o endpoint de comprovantes `/api/customer/freight_delivery_receipts?cte_key=...` e, quando necessário, o endpoint de XML do CT-e. São utilizados tokens ESL distintos para os fluxos da PPG e da Vedacit, mantendo isolamento operacional e cursor próprio para cada destino.

**2. PPG / OK Entrega — destino REST:** a aplicação realiza login no endpoint `/assets/ws/ws.0.loginapp.php` e conserva o token em memória por 13 dias. O envio da ocorrência é realizado em JSON para `/assets/ws/ws.0.ocorrenciaentregacache_api.php`, com *access token*. O canhoto é enviado já normalizado no padrão técnico exigido pela PPG.

**3. Vedacit / MultiTMS — destino SOAP:** a integração utiliza os serviços `Ocorrencias.svc`, `NFe.svc` e `CTe.svc`. As operações enviam ocorrência, digitalização de canhoto e, quando habilitado, XML de CT-e. Os contratos SOAP são gerados a partir dos WSDLs oficiais e o token é aplicado no cabeçalho SOAP. Endpoints e *timeouts* são configuráveis por ambiente.

**4. API de auditoria do Satélite TMS:** o endpoint `/api/auditoria/integracoes-clientes` retorna métricas consolidadas e registros paginados de auditoria. Ele suporta período, escopo de pendências ou sucessos, busca textual, chave ou número da nota, status, filtros por coluna, ordenação e paginação. O endpoint `/api/auditoria/integracoes-clientes/evolucao-diaria` retorna a série diária de volume, sucessos e erros. Também estão disponíveis endpoints de resumo por etapa, consulta de canhoto por identificador de log e listagem de quarentena.

**5. Portal de Dashboards — consumo da auditoria:** o front-end React consome a rota protegida `/api/painel/integracoes` por Axios e React Query. A API do portal funciona como uma camada intermediária com controle de acesso e comunicação interna com o Satélite. Essa integração transforma os logs técnicos em KPIs, gráficos, tabelas e alertas operacionais, sem expor o banco de auditoria diretamente ao navegador.

## 4.6. Dependências Técnicas

- JDK/JRE compatível com **Java 17** e Maven Wrapper para compilação e execução do serviço.
- Microsoft SQL Server acessível pela aplicação, com a base `SATELITE_TMS_AUDITORIA` criada pelos scripts versionados do projeto.
- Conectividade de rede com as APIs da Rodogarcia/ESL Cloud, PPG/OK Entrega, Vedacit/MultiTMS e com a API interna do portal de dashboards.
- Liberações de DNS, firewall, proxy, VPN ou lista de IPs, quando exigidas pelos parceiros.
- Variáveis de ambiente ou arquivo `.env` configurados com URLs, tokens, credenciais, parâmetros de banco, *timeouts*, intervalos de execução, listas de NF-e permitidas e chaves de ativação dos fluxos. Nenhum segredo é fixado no código-fonte.
- Acesso aos WSDLs oficiais da Vedacit durante a geração dos clientes SOAP e disponibilidade dos WSDLs/XSDs empacotados no artefato da aplicação para execução.
- `sqlcmd`, quando necessário, para provisionar ou atualizar o banco por meio do script `database/subir_database.bat`.
- Para o monitoramento, portal de dashboards publicado com a URL interna do Satélite configurada e usuários com permissão de acesso ao módulo “Integrações”.

## 4.7. Link do Repositório

https://github.com/valentelucass/satelite-tms-api
