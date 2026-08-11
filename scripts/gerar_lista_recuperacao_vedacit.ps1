param(
    [string]$ArquivoBase = "docs\SECRETO\MULTITMS\analise_vedacit_comprovantes(MultiTMS).csv",
    [string]$ArquivoSaida = "docs\SECRETO\MULTITMS\nfes_recuperacao_vedacit.txt"
)

$ErrorActionPreference = 'Stop'
$raizProjeto = Split-Path -Parent $PSScriptRoot
Set-Location $raizProjeto

$envMap = @{}
Get-Content '.env' | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') {
        $envMap[$matches[1].Trim()] = $matches[2].Trim().Trim('"')
    }
}

if ($envMap['DB_NAME'] -ne 'SATELITE_TMS_AUDITORIA') {
    throw "Database nao permitida: $($envMap['DB_NAME'])"
}

$base = Get-Content -LiteralPath $ArquivoBase -Encoding Default | ConvertFrom-Csv -Delimiter ';'
if ($base.Count -eq 0) {
    throw 'A base Vedacit nao possui linhas.'
}

$colunas = $base[0].PSObject.Properties.Name
$colunaNfe = $colunas[2]
$colunaEsl = $colunas[9]
$connectionString = "Server=$($envMap['DB_HOST']),$($envMap['DB_PORT']);Database=$($envMap['DB_NAME']);User ID=$($envMap['DB_USER']);Password=$($envMap['DB_PASSWORD']);Encrypt=True;TrustServerCertificate=True;"
$connection = [System.Data.SqlClient.SqlConnection]::new($connectionString)
$command = $connection.CreateCommand()
$command.CommandText = "SELECT DISTINCT chave_nfe FROM dbo.tb_log_integracao WHERE sistema_destino = 'VEDACIT' AND chave_nfe IS NOT NULL"

$chavesAuditadas = [System.Collections.Generic.HashSet[string]]::new()
try {
    $connection.Open()
    $reader = $command.ExecuteReader()
    while ($reader.Read()) {
        [void]$chavesAuditadas.Add([string]$reader['chave_nfe'])
    }
    $reader.Close()
} finally {
    $connection.Dispose()
}

$chavesRecuperacao = $base |
    Where-Object {
        $chave = [string]$_.$colunaNfe
        $_.$colunaEsl -eq 'Sim' -and $chave -match '^\d{44}$' -and -not $chavesAuditadas.Contains($chave)
    } |
    ForEach-Object { [string]$_.$colunaNfe } |
    Sort-Object -Unique

$diretorioSaida = Split-Path -Parent $ArquivoSaida
New-Item -ItemType Directory -Force -Path $diretorioSaida | Out-Null
Set-Content -LiteralPath $ArquivoSaida -Value $chavesRecuperacao -Encoding ascii
Write-Output "Lista gerada: $ArquivoSaida"
Write-Output "NF-es elegiveis: $($chavesRecuperacao.Count)"
