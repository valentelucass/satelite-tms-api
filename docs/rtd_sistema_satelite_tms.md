# RTD - Sistema Satélite TMS

## 1.1 Linguagens de Programação Utilizadas

O Sistema Satélite TMS foi desenvolvido em Java 17 LTS, versão configurada no `pom.xml` do projeto por meio da propriedade `<java.version>17</java.version>`.

O gerenciamento de build, dependências e empacotamento da aplicação é realizado com Apache Maven 3.9.16, disponibilizado pelo Maven Wrapper do projeto.

Para a camada de auditoria, o sistema utiliza SQL/T-SQL sobre Microsoft SQL Server, restrito à database `SATELITE_TMS_AUDITORIA`.

## 1.2 Frameworks / Bibliotecas

O sistema utiliza Spring Boot 4.1.0 como framework principal da aplicação.

A comunicação REST com sistemas externos é realizada por Spring Cloud OpenFeign, utilizado nos clientes da Rodogarcia / ESL Cloud e da PPG / OK Entrega.

A persistência de auditoria utiliza Spring Data JPA, Hibernate/JPA e o driver JDBC oficial do Microsoft SQL Server.

A integração SOAP com Vedacit / MultiTMS utiliza Jakarta XML Web Services API, JAX-WS RI Runtime e `jaxws-maven-plugin` para geração automática das classes a partir dos WSDLs oficiais.

O projeto também utiliza Lombok para redução de boilerplate em classes internas, Jackson para serialização e desserialização JSON, e APIs Java SE para download e tratamento de imagens.

## 1.3 Plataformas de Apoio

A aplicação é executada como processo Java em ambiente Windows, com suporte operacional pelo script `satelite.bat`.

O Microsoft SQL Server é utilizado como plataforma de banco de dados para auditoria, controle de cursor, rastreabilidade de processamento e registro de falhas.

As plataformas externas integradas pela solução são:

- Rodogarcia / ESL Cloud, origem das ocorrências e comprovantes de entrega.
- PPG / OK Entrega, destino REST para baixa de ocorrências.
- Vedacit / MultiTMS, destino SOAP para ocorrências e digitalização de canhotos.

## 1.4 Arquitetura / Fluxo da Solução

O Sistema Satélite TMS segue uma arquitetura de middleware ETL, executada de forma headless e acionada por agendamento interno via Spring Scheduling.

Na etapa de extração, o sistema consulta a Rodogarcia / ESL Cloud por meio de APIs REST. O fluxo principal consome ocorrências pelo endpoint `/api/customer/invoice_occurrences`, utilizando autenticação `Bearer Token` e paginação por cursor `paging.next_id`. Os comprovantes de entrega são consultados por `cte_key`.

Na etapa de transformação, o sistema normaliza os dados recebidos da ESL para os contratos de destino. O orquestrador aplica o filtro obrigatório `occurrence.code == 1`, processando somente eventos de entrega realizada. Ocorrências com outros códigos são registradas em auditoria como ignoradas.

O processamento de imagem é realizado antes do envio aos destinos. Para PPG, o comprovante é convertido para JPEG em RGB, sem canal alfa, com fundo branco, dimensão final de `1536x240` pixels e prefixo `data:image/jpeg;base64,`. Para Vedacit, o canhoto é enviado como Base64 bruto, sem prefixo MIME.

Na etapa de carga, a PPG / OK Entrega é integrada via REST JSON. A autenticação é feita por login no destino, e o token retornado é mantido em memória com renovação preventiva em 13 dias.

A Vedacit / MultiTMS é integrada via SOAP. O sistema envia a ocorrência pelo serviço `Ocorrencias.svc` e a digitalização do canhoto pelo serviço `NFe.svc`, utilizando token no header SOAP.

A idempotência e a rastreabilidade são controladas pela tabela `dbo.tb_log_integracao`, que registra status de processamento, destino, identificadores da ocorrência, chave NFe, cursor, erros e datas de processamento.

## 1.5 Dependências Técnicas

O funcionamento da solução depende de conectividade HTTPS com as APIs da Rodogarcia / ESL Cloud, PPG / OK Entrega e Vedacit / MultiTMS.

Também são necessárias as seguintes dependências técnicas:

- JDK/JRE compatível com Java 17 ou superior.
- Microsoft SQL Server acessível pela aplicação.
- Database `SATELITE_TMS_AUDITORIA` criada e disponível.
- Variáveis de ambiente configuradas no `.env` ou no ambiente do servidor.
- Liberações de firewall, DNS, proxy, VPN ou white-list de IP quando exigidas pelos provedores externos.
- `sqlcmd` para execução dos scripts de banco quando houver necessidade operacional.

## 2. Modelagem do Sistema

O Sistema Satélite TMS atua como um hub de integração entre a origem Rodogarcia / ESL Cloud e os destinos PPG / OK Entrega e Vedacit / MultiTMS.

A solução é organizada em quatro camadas principais:

### 2.1 Camada de Ingestão

A camada de ingestão consulta a ESL Cloud por meio de clientes REST declarativos com OpenFeign.

Essa camada é responsável por:

- consultar ocorrências por cliente/destino;
- aplicar tokens ESL específicos para PPG e Vedacit;
- controlar paginação por `paging.next_id`;
- respeitar intervalo mínimo entre requisições;
- buscar comprovantes de entrega vinculados ao CTe.

### 2.2 Camada de Processamento

A camada de processamento aplica as regras de negócio do ETL antes do envio aos destinos.

Essa camada é responsável por:

- desserializar os contratos da ESL;
- filtrar somente ocorrências de entrega realizada;
- registrar ocorrências ignoradas;
- obter e baixar imagens de comprovante;
- converter imagem para o padrão exigido pela PPG;
- preparar Base64 bruto para o envio SOAP da Vedacit.

### 2.3 Camada de Distribuição

A camada de distribuição envia os dados transformados aos sistemas de destino.

Para PPG / OK Entrega, o envio ocorre via REST JSON, com autenticação por token em memória.

Para Vedacit / MultiTMS, o envio ocorre via SOAP, com classes geradas automaticamente a partir dos WSDLs oficiais e token aplicado no header da mensagem.

### 2.4 Camada de Auditoria

A camada de auditoria registra a execução do ETL no SQL Server, por meio da tabela `dbo.tb_log_integracao`.

Essa camada é responsável por:

- registrar ocorrências recebidas;
- registrar itens ignorados;
- registrar sucesso ou falha por destino;
- armazenar o cursor de continuidade;
- evitar reenvio de ocorrências já finalizadas;
- permitir rastreabilidade operacional por `occurrence_id`, `chave_nfe`, `sistema_destino` e `status`.

## 3. Fluxo de Dados

```text
Rodogarcia / ESL Cloud
        |
        | REST - ocorrências e comprovantes
        v
Sistema Satélite TMS
        |
        |-- REST JSON --> PPG / OK Entrega
        |
        |-- SOAP XML  --> Vedacit / MultiTMS
        |
        '-- SQL Server --> SATELITE_TMS_AUDITORIA / dbo.tb_log_integracao
```

O modelo separa a origem, as regras de transformação, os destinos e a auditoria em responsabilidades distintas. Dessa forma, alterações em uma integração tendem a ficar restritas ao respectivo módulo, preservando baixo acoplamento e maior previsibilidade de manutenção.
