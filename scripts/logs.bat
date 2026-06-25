@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat"
if errorlevel 1 exit /b 1

set "SATELITE_LOGS_DIR=%LOGS_DIR%"
set "SATELITE_LOGS_SCRIPT=%~f0"

powershell -NoProfile -ExecutionPolicy Bypass -Command "$content = Get-Content -Raw -LiteralPath $env:SATELITE_LOGS_SCRIPT; $parts = $content -split '(?m)^:POWERSHELL\s*$', 2; if ($parts.Count -lt 2) { throw 'Bloco PowerShell embarcado nao encontrado.' }; Invoke-Expression $parts[1]"

exit /b 0

:POWERSHELL
$ErrorActionPreference = 'Stop'

$utf8 = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = $utf8
[Console]::InputEncoding = $utf8
$OutputEncoding = $utf8

$root = $env:SATELITE_LOGS_DIR
Write-Host "Procurando logs em $root..."

if ([string]::IsNullOrWhiteSpace($root) -or -not (Test-Path -LiteralPath $root -PathType Container)) {
    Write-Host 'Nenhum log encontrado.'
    exit 0
}

$fileInfo = Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.log' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $fileInfo) {
    Write-Host 'Nenhum log encontrado.'
    exit 0
}

$file = $fileInfo.FullName
Write-Host "Arquivo: $file"
Write-Host ''
Write-Host '=== Logs ao vivo (ultimas 50 linhas). Pressione Q para voltar ao menu. ==='
Write-Host ''

$stream = [System.IO.File]::Open(
    $file,
    [System.IO.FileMode]::Open,
    [System.IO.FileAccess]::Read,
    ([System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete)
)

$reader = $null

try {
    $reader = New-Object System.IO.StreamReader -ArgumentList $stream, $utf8, $true
    $tail = New-Object 'System.Collections.Generic.Queue[string]'

    while (($line = $reader.ReadLine()) -ne $null) {
        $tail.Enqueue($line)

        while ($tail.Count -gt 50) {
            [void]$tail.Dequeue()
        }
    }

    foreach ($line in $tail) {
        Write-Host $line
    }

    while ($true) {
        $keyAvailable = $false

        try {
            $keyAvailable = [Console]::KeyAvailable
        } catch {
            $keyAvailable = $false
        }

        if ($keyAvailable) {
            $key = [Console]::ReadKey($true)

            if ($key.Key -eq [System.ConsoleKey]::Q) {
                break
            }
        }

        $line = $reader.ReadLine()

        if ($null -ne $line) {
            Write-Host $line
        } else {
            Start-Sleep -Milliseconds 250
        }
    }
} finally {
    if ($reader) {
        $reader.Close()
    } elseif ($stream) {
        $stream.Close()
    }
}

Write-Host ''
Write-Host 'Voltando ao menu...'
