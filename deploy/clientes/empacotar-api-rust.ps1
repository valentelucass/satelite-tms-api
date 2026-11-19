[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$modulo = Join-Path $repo 'backend/rust'
$pacotes = Join-Path $PSScriptRoot 'pacotes'
Push-Location $modulo
try {
    & cargo test --release --locked
    if ($LASTEXITCODE -ne 0) { throw 'Falha nos testes da API Rust.' }
    & cargo build --release --locked
    if ($LASTEXITCODE -ne 0) { throw 'Falha na compilação da API Rust.' }
    $meta = & cargo metadata --locked --no-deps --format-version 1 | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao localizar a compilação Rust.' }
    $executavel = Join-Path $meta.target_directory 'release/satelite-api-piloto.exe'
    $hash = (Get-FileHash -LiteralPath $executavel -Algorithm SHA256).Hash.ToLowerInvariant()
    $pasta = Join-Path $pacotes "rust/$hash"
    $arquivo = Join-Path $pasta 'satelite-tms-api.exe'
    [IO.Directory]::CreateDirectory($pasta) | Out-Null
    if (!(Test-Path -LiteralPath $arquivo -PathType Leaf)) {
        $tmpExe = Join-Path $pasta ([guid]::NewGuid().ToString('N') + '.tmp')
        try {
            [IO.File]::Copy($executavel, $tmpExe)
            if ((Get-FileHash -LiteralPath $tmpExe).Hash -ne $hash) { throw 'Cópia da API divergente.' }
            try { [IO.File]::Move($tmpExe, $arquivo) } catch [IO.IOException] {
                if (!(Test-Path -LiteralPath $arquivo -PathType Leaf)) { throw }
            }
        } finally { if (Test-Path -LiteralPath $tmpExe) { Remove-Item -LiteralPath $tmpExe } }
    }
    if ((Get-FileHash -LiteralPath $arquivo).Hash -ne $hash) { throw 'O pacote Rust não confere com a compilação testada.' }
    $ponteiro = Join-Path $pacotes 'api-rust.txt'
    $tmp = Join-Path $pacotes ([guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllText($tmp, $hash)
        if ([IO.File]::Exists($ponteiro)) {
            [IO.File]::Replace($tmp, $ponteiro, [System.Management.Automation.Language.NullString]::Value)
        } else { [IO.File]::Move($tmp, $ponteiro) }
    } finally { if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp } }
    Write-Output $arquivo
} finally { Pop-Location }
