# Destino PPG - OK Entrega

## Autenticacao

| Item | Valor |
|---|---|
| Endpoint | `POST ${PPG_API_BASE_URL}/api/Usuarios/login` |
| Usuario | `${PPG_API_USER}` |
| Senha | `${PPG_API_PASSWORD}` |
| Retorno esperado | `AccessToken.id` |

O token retornado deve ser usado nas chamadas REST do OK Entrega conforme o padrao LoopBack aceito pelo ambiente.

## Contrato de ocorrencia

| Item | Valor |
|---|---|
| Metodo | `POST` |
| Endpoint | `${PPG_API_BASE_URL}/api/Ocorrenciaentregas` |
| Content-Type | `application/json` |
| Evento ESL aceito | `occurrence.code == 1` |

Payload base:

```json
{
  "documento": "44_DIGITOS_CHAVE_NFE",
  "documento_34": "VALOR_EXIGIDO_PELO_OK_ENTREGA",
  "tipoocorrenciaId": 1,
  "tipoentrega": "F",
  "cnpjtransportadora": "CNPJ_TRANSPORTADORA",
  "entregadorId": 29544,
  "dtentrega": "2026-05-18T08:41:01.000-03:00",
  "dtreentrega": null,
  "dtsinistro": null,
  "dtregistro": "2026-05-18T08:41:01.000-03:00",
  "tipoentrada": "I",
  "latitude": "0",
  "longitude": "0",
  "motivoocorrenciaId": null,
  "pendencia": false,
  "criacaoToken": "satelite-tms",
  "alteracaoToken": "satelite-tms",
  "status": true,
  "ocorrenciaentregafoto": [
    {
      "tipofoto": "C",
      "foto": "data:image/jpeg;base64,BASE64_JPEG_NORMALIZADO",
      "mime": "data:image/jpeg;base64",
      "extensao": "jpeg",
      "criacaoToken": "satelite-tms",
      "alteracaoToken": "satelite-tms",
      "status": true
    }
  ]
}
```

## Campos obrigatorios do Swagger

Modelo `Ocorrenciaentrega`:

| Campo | Tipo Java | Regra |
|---|---|---|
| `documento` | `String` | Chave NFe com 44 digitos. |
| `documento_34` | `String` | Campo obrigatorio do OK Entrega; preencher conforme regra cadastrada no destino. |
| `tipoocorrenciaId` | `Integer` | `1` para entrega. |
| `entregadorId` | `Integer` | `${PPG_ENTREGADOR_ID}`. |
| `dtregistro` | `OffsetDateTime` | Usar `occurrence_at`. |
| `tipoentrada` | `String` | Valor fixo `I`. |
| `latitude` | `String` | Usar coordenada disponivel; se ausente, enviar `"0"`. |
| `longitude` | `String` | Usar coordenada disponivel; se ausente, enviar `"0"`. |
| `pendencia` | `Boolean` | `false` para entrega normal. |
| `criacaoToken` | `String` | Identificador do integrador, ex.: `satelite-tms`. |
| `alteracaoToken` | `String` | Identificador do integrador, ex.: `satelite-tms`. |
| `status` | `Boolean` | `true` para registro ativo. |

Modelo `Ocorrenciaentregafoto`:

| Campo | Tipo Java | Regra |
|---|---|---|
| `ocorrenciaentregaId` | `Long` | Obrigatorio quando a foto for enviada em endpoint separado. |
| `tipofoto` | `String` | `C` para canhoto. |
| `foto` | `String` | `data:image/jpeg;base64,` + Base64 da imagem normalizada. |
| `mime` | `String` | `data:image/jpeg;base64`. |
| `extensao` | `String` | `jpeg`. |
| `criacaoToken` | `String` | Identificador do integrador. |
| `alteracaoToken` | `String` | Identificador do integrador. |
| `status` | `Boolean` | `true`. |

## Normalizacao binaria do canhoto

Regra estrita antes de montar o campo `foto`:

| Propriedade | Valor obrigatorio |
|---|---|
| Formato | JPEG |
| Largura | `1536` pixels |
| Altura | `240` pixels |
| Resolucao | `150 dpi` |
| Cor | RGB, sem canal alfa |
| Prefixo | `data:image/jpeg;base64,` |

Pipeline recomendado:

1. Ler os bytes originais do canhoto.
2. Aplicar orientacao EXIF.
3. Converter para RGB.
4. Ajustar para canvas final `1536x240`.
5. Preservar proporcao sempre que possivel; preencher sobras com fundo branco.
6. Gravar JPEG com metadado de `150 dpi`.
7. Codificar em Base64.
8. Concatenar `data:image/jpeg;base64,` + Base64.

## Mapeamento ESL para PPG

| ESL | PPG | Regra |
|---|---|---|
| `invoice.key` | `documento` | Chave NFe, 44 digitos. |
| `occurrence.code` | `tipoocorrenciaId` | Enviar somente se `1`; valor PPG `1`. |
| `occurrence_at` | `dtentrega` | Data/hora da entrega. |
| `occurrence_at` | `dtregistro` | Data/hora do registro. |
| Configuracao | `entregadorId` | `${PPG_ENTREGADOR_ID}`. |
| Constante | `tipoentrada` | `I`. |
| Constante | `tipoentrega` | `F`. |
| Constante | `motivoocorrenciaId` | `null` para entrega normal. |
| Canhoto normalizado | `ocorrenciaentregafoto[].foto` | Data URI JPEG Base64. |
