# Destino Vedacit - MultiTMS

## Servicos SOAP

| Servico | URL |
|---|---|
| Cargas | `${VEDACIT_API_BASE_URL}/Cargas.svc` |
| NFe | `${VEDACIT_API_BASE_URL}/NFe.svc` |
| Ocorrencias | `${VEDACIT_API_BASE_URL}/Ocorrencias.svc` |

## Header de autenticacao

```xml
<soapenv:Header>
  <Token xmlns="Token">${VEDACIT_API_TOKEN}</Token>
</soapenv:Header>
```

## Operacoes

| Metodo | Servico | Uso |
|---|---|---|
| `BuscarCargasPendentesIntegracao` | `Cargas.svc` | Consultar cargas pendentes quando o fluxo exigir reconciliacao de carga. |
| `BuscarCarga` | `Cargas.svc` | Consultar detalhes por protocolo. |
| `ConfirmarIntegracaoCarga` | `Cargas.svc` | Confirmar carga integrada. |
| `BuscarNotasFiscaisVinculadas` | `NFe.svc` | Consultar NFs por carga/protocolo. |
| `AdicionarOcorrencia` | `Ocorrencias.svc` | Enviar entrega realizada. |
| `EnviarDigitalizacaoCanhoto` | `NFe.svc` | Enviar imagem do canhoto. |

## Envelope - AdicionarOcorrencia

```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tem="http://tempuri.org/">
  <soapenv:Header>
    <Token xmlns="Token">${VEDACIT_API_TOKEN}</Token>
  </soapenv:Header>
  <soapenv:Body>
    <tem:AdicionarOcorrencia>
      <tem:ocorrencia>
        <DataOcorrencia>18/05/2026 08:41:01</DataOcorrencia>
        <NumeroNotaFiscal>418454</NumeroNotaFiscal>
        <SerieNotaFiscal>17</SerieNotaFiscal>
        <Latitude>0</Latitude>
        <Longitude>0</Longitude>
        <Observacao>Entrega integrada pelo Satelite TMS</Observacao>
        <Remetente>
          <CPFCNPJ>CNPJ_REMETENTE</CPFCNPJ>
        </Remetente>
        <Destinatario>
          <CPFCNPJ>CNPJ_DESTINATARIO</CPFCNPJ>
        </Destinatario>
        <TipoOcorrencia>
          <CodigoIntegracao>001</CodigoIntegracao>
          <Descricao>Entrega Realizada</Descricao>
        </TipoOcorrencia>
      </tem:ocorrencia>
    </tem:AdicionarOcorrencia>
  </soapenv:Body>
</soapenv:Envelope>
```

Mapeamento:

| ESL | SOAP `AdicionarOcorrencia` | Regra |
|---|---|---|
| `occurrence_at` | `DataOcorrencia` | Formatar como `dd/MM/yyyy HH:mm:ss`. |
| `invoice.number` | `NumeroNotaFiscal` | Converter para numero quando aceito pelo destino. |
| `invoice.series` | `SerieNotaFiscal` | Preservar como texto. |
| `occurrence.code` | `TipoOcorrencia.CodigoIntegracao` | Para ESL `1`, enviar `001`. |
| `occurrence.description` | `TipoOcorrencia.Descricao` | Enviar descricao de entrega. |
| Dados complementares | `Observacao` | Texto curto de rastreabilidade. |
| Coordenadas | `Latitude`, `Longitude` | Enviar `"0"` quando ausentes. |

## Envelope - EnviarDigitalizacaoCanhoto

```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tem="http://tempuri.org/">
  <soapenv:Header>
    <Token xmlns="Token">${VEDACIT_API_TOKEN}</Token>
  </soapenv:Header>
  <soapenv:Body>
    <tem:EnviarDigitalizacaoCanhoto>
      <tem:canhoto>
        <ChaveAcesso>44_DIGITOS_CHAVE_NFE</ChaveAcesso>
        <DataEntregaNota>18/05/2026 08:41:01</DataEntregaNota>
        <DataEnvioCanhoto>18/05/2026 08:41:01</DataEnvioCanhoto>
        <ImagemCanhotoBase64>BASE64_BRUTO_SEM_PREFIXO_MIME</ImagemCanhotoBase64>
        <NomeImagemCanhoto>canhoto_44_DIGITOS_CHAVE_NFE.jpg</NomeImagemCanhoto>
        <Latitude>0</Latitude>
        <Longitude>0</Longitude>
        <NumeroNotaFiscal>418454</NumeroNotaFiscal>
        <SerieNotaFiscal>17</SerieNotaFiscal>
        <Observacao>Canhoto integrado pelo Satelite TMS</Observacao>
      </tem:canhoto>
    </tem:EnviarDigitalizacaoCanhoto>
  </soapenv:Body>
</soapenv:Envelope>
```

Mapeamento:

| ESL / ETL | SOAP `EnviarDigitalizacaoCanhoto` | Regra |
|---|---|---|
| `invoice.key` | `ChaveAcesso` | Chave NFe com 44 digitos. |
| `occurrence_at` | `DataEntregaNota` | Formatar como `dd/MM/yyyy HH:mm:ss`. |
| Data de envio | `DataEnvioCanhoto` | Data/hora do processamento. |
| Imagem normalizada/capturada | `ImagemCanhotoBase64` | Base64 bruto, sem `data:image/jpeg;base64,`. |
| Nome gerado | `NomeImagemCanhoto` | Nome deterministico por chave NFe. |
| `invoice.number` | `NumeroNotaFiscal` | Numero da NF. |
| `invoice.series` | `SerieNotaFiscal` | Serie da NF. |
| Coordenadas | `Latitude`, `Longitude` | Enviar `"0"` quando ausentes. |

## Regra do canhoto

`ImagemCanhotoBase64` deve conter somente a string Base64 crua.

Correto:

```text
/9j/4AAQSkZJRgABAQ...
```

Incorreto:

```text
data:image/jpeg;base64,/9j/4AAQSkZJRgABAQ...
```
