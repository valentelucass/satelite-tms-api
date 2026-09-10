[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Manifesto,
    [Parameter(Mandatory)][string]$Jar,
    [switch]$Enviar,
    [switch]$SomentePrimeiroLote,
    [ValidateRange(1,200)][int]$TamanhoLote = 10
)
$ErrorActionPreference = 'Stop'
$raiz = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$arquivo = (Resolve-Path -LiteralPath $Manifesto).ProviderPath
$pacote = (Resolve-Path -LiteralPath $Jar).ProviderPath
$java = Join-Path $raiz '.tools/temurin17/jdk-17.0.19+10/bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { throw 'Java 17 portátil não encontrado.' }
# Execução foreground iniciada pelo operador. Não gerencia PM2 nem altera configuração.
$argumentos = @('-Dfile.encoding=UTF-8', '-jar', $pacote,
    '--spring.main.web-application-type=none', '--server.port=0', '--APP_DASHBOARD_API_ONLY=false',
    '--APP_SCHEDULER_ENABLED=false', '--APP_CICLO_UNICO=false', '--retroactive.enabled=false', '--RETROACTIVE_ENABLED=false',
    '--APP_NIGHTLY_RETRY_ENABLED=false', '--APP_ETL_REPESCAGEM_ENABLED=false',
    '--work.sftp-clientes.enabled=false', '--APP_PPG_ENABLED=false', '--APP_SELIA_ENABLED=false',
    '--APP_SUPPORTE_ENABLED=false', '--APP_VEDACIT_ENABLED=true', '--VEDACIT_SEND_CTE_XML_ENABLED=true',
    '--SFTP_RODOGARCIA_ENABLED=true', '--VEDACIT_SFTP_RECEIPT_ONLY=true',
    '--VEDACIT_NFE_WHITELIST_ENABLED=false', '--vedacit.recovery.enabled=true',
    "--vedacit.recovery.nfe-file=$arquivo", "--vedacit.recovery.preview=$((!$Enviar).ToString().ToLowerInvariant())",
    "--vedacit.recovery.drain-enabled=$((!$SomentePrimeiroLote).ToString().ToLowerInvariant())",
    "--vedacit.recovery.max-items=$TamanhoLote", '--vedacit.recovery.interval-ms=1000')
Push-Location -LiteralPath $raiz
try { & $java @argumentos; $resultado = $LASTEXITCODE } finally { Pop-Location }
exit $resultado
