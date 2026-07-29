$ErrorActionPreference = "Stop"

$LoginUrl = "https://hml.okentrega.com.br/assets/ws/ws.0.loginapp.php"
$OcorrenciaUrlBase = "https://hml.okentrega.com.br/assets/ws/ws.0.ocorrenciaentregacache_api.php"

$LoginPayload = @'
{
  "email": "rodogarcia@okentrega.com.br",
  "password": "homolog"
}
'@

$OcorrenciaPayload = @'
{
  "documento": "35211059775478000721550020003677951100079999",
  "tipoocorrenciaId": 1,
  "tipoentrega": "F",
  "cnpjtransportadora": "12345678000199",
  "entregadorId": 29544,
  "dtentrega": "2026-06-22T13:56:09.141Z",
  "dtreentrega": null,
  "dtsinistro": null,
  "dtregistro": "2026-06-22T13:56:09.141Z",
  "tipoentrada": "I",
  "latitude": "0",
  "longitude": "0",
  "motivoocorrenciaId": null,
  "ocorrenciaentregafoto": [
    {
      "tipofoto": "C",
      "foto": "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=",
      "mime": "data:image/jpeg;base64",
      "extensao": "jpeg"
    }
  ]
}
'@

function Invoke-CurlJsonPost {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Url,

        [Parameter(Mandatory = $true)]
        [string] $JsonPayload
    )

    $bodyFile = New-TemporaryFile
    $payloadFile = New-TemporaryFile 

    try {
        # NOVO: Força o UTF-8 SEM os caracteres invisíveis (BOM)
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($payloadFile.FullName, $JsonPayload, $utf8NoBom)

        $httpCode = & curl.exe `
            --silent `
            --show-error `
            --location `
            --request POST `
            --header "Content-Type: application/json" `
            --data-binary "@$($payloadFile.FullName)" `
            --output $bodyFile.FullName `
            --write-out "%{http_code}" `
            $Url

        if ($LASTEXITCODE -ne 0) {
            throw "curl.exe falhou com exit code $LASTEXITCODE"
        }

        $body = Get-Content -Raw -LiteralPath $bodyFile.FullName

        return [pscustomobject]@{
            StatusCode = [int] $httpCode
            Body = $body
        }
    }
    finally {
        Remove-Item -LiteralPath $bodyFile.FullName -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $payloadFile.FullName -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "Passo 1: efetuando login na OK Entrega..."
$loginResponse = Invoke-CurlJsonPost -Url $LoginUrl -JsonPayload $LoginPayload
Write-Host "Login Response Code: $($loginResponse.StatusCode)"

try {
    $loginJson = $loginResponse.Body | ConvertFrom-Json
}
catch {
    Write-Host "Login Response Body:"
    Write-Host $loginResponse.Body
    throw "Resposta de login nao esta em JSON valido."
}

if ([string]::IsNullOrWhiteSpace($loginJson.id)) {
    Write-Host "Login Response Body:"
    Write-Host $loginResponse.Body
    throw "Token nao encontrado na propriedade 'id' da resposta de login."
}

$token = [string] $loginJson.id
$tokenEncoded = [uri]::EscapeDataString($token)

Write-Host "Token extraido com sucesso."
Write-Host "Passo 2: enviando ocorrencia para a OK Entrega..."

$ocorrenciaUrl = "${OcorrenciaUrlBase}?access_token=${tokenEncoded}"
$ocorrenciaResponse = Invoke-CurlJsonPost -Url $ocorrenciaUrl -JsonPayload $OcorrenciaPayload

Write-Host "Ocorrencia Response Code: $($ocorrenciaResponse.StatusCode)"
Write-Host "Ocorrencia Response Body:"
Write-Host $ocorrenciaResponse.Body
