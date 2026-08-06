# Destino SUPPORTE — ocorrências e comprovantes

## Contrato

- Endpoint: `${SUPPORTE_API_BASE_URL}${SUPPORTE_OCCURRENCES_PATH}`
- Método: `POST`
- Header: `Authorization: Basic <token>` e `Content-Type: application/json`
- A requisição contém data de envio, CNPJ da transportadora, CNPJ pagador, NF-e, CT-e e ocorrência.
- Os campos de data usam `dd-MM-yyyy HH:mm:ss` no fuso `America/Sao_Paulo`.
- A chave de acesso da NF-e e do CT-e é validada com 44 dígitos. A série e o número do CT-e são extraídos da chave oficial; a chave nunca é construída ou alterada.

## Elegibilidade e comprovante

- O ETL consulta exclusivamente `occurrence.code == 1` e o serviço mantém essa validação defensiva.
- A NF-e é elegível somente quando o CNPJ emitente do CT-e, obtido da chave de acesso do CT-e, estiver em `SUPPORTE_CNPJ_PAGADORES`; esse CNPJ também compõe `cnpjPagador`.
- Sem comprovante, a ocorrência é enviada imediatamente e permanece com canhoto `PENDENTE_FOTO` para nova consulta.
- Quando o comprovante estiver disponível, o Satélite o baixa, normaliza para JPEG e o envia em `imagemComprovante` com o prefixo `data:image/jpeg;base64,`.
- Respostas de duplicidade/finalização já existente são conciliadas como sucesso. Erros de processamento temporários, erros de contrato e respostas não reconhecidas preservam rastreabilidade como falha do destino.

## Configuração operacional

As variáveis ficam exclusivamente no `.env`:

- `APP_SUPPORTE_ENABLED`
- `RODOGARCIA_TOKEN_SUPPORTE` e `RODOGARCIA_TOKEN_SUPPORTE_COMPROVANTE`
- `SUPPORTE_API_BASE_URL`, `SUPPORTE_OCCURRENCES_PATH` e `SUPPORTE_API_AUTHORIZATION`
- `SUPPORTE_CNPJ_TRANSPORTADORA` e `SUPPORTE_CNPJ_PAGADORES`
- `SUPPORTE_NFE_WHITELIST_ENABLED`, `SUPPORTE_NFE_WHITELIST`, `SUPPORTE_CONNECT_TIMEOUT_MS` e `SUPPORTE_READ_TIMEOUT_MS`

O destino inicia desabilitado. Para uma homologação controlada, informar o token ESL exclusivo, preencher uma única chave NF-e na whitelist e então habilitar `APP_SUPPORTE_ENABLED`.
