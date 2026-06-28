@echo off
setlocal EnableExtensions
title Satelite TMS - API Server
color 0A

chcp 65001 >nul

:: Navega para a pasta raiz do projeto.
cd /d "%~dp0"

:: Porta dedicada do modo API.
:: Mantem este runtime isolado do menu satelite.bat e de outras apps Java.
if "%SATELITE_API_PORT%"=="" set "SATELITE_API_PORT=19090"

call "%~dp0scripts\_common.bat" require-jar validate-db
if errorlevel 1 exit /b 1

set "SERVER_PORT=%SATELITE_API_PORT%"
set "PORTA_API=%SATELITE_API_PORT%"

:: start_api.bat sobe SOMENTE a API. Nunca herda o scheduler do .env.
set "APP_SCHEDULER_ENABLED=false"
set "APP_CICLO_UNICO=false"
if "%APP_PPG_ENABLED%"=="" set "APP_PPG_ENABLED=true"
if "%APP_VEDACIT_ENABLED%"=="" set "APP_VEDACIT_ENABLED=true"
if "%INTEGRATION_SCHEDULER_INTERVAL_MS%"=="" set "INTEGRATION_SCHEDULER_INTERVAL_MS=%BACKGROUND_INTERVAL_MS%"
set "START_API_OUT_LOG=%LOGS_DIR%\start_api.out.log"
set "START_API_ERR_LOG=%LOGS_DIR%\start_api.err.log"
set "START_API_SCRIPT=%~f0"

cls
echo +------------------------------------------------------+
echo ^| SATELITE TMS - SOMENTE API                         ^|
echo +------------------------------------------------------+
echo.
echo Status: iniciando Spring Boot...
echo Porta : %SATELITE_API_PORT%
echo URL   : http://127.0.0.1:%SATELITE_API_PORT%
echo Scheduler: DESATIVADO neste script.
echo Extracao incremental: NAO sera iniciada automaticamente.
echo.
echo Logs:
echo   %START_API_OUT_LOG%
echo   %START_API_ERR_LOG%
echo.
echo Aguardando a API confirmar a porta...
echo +------------------------------------------------------+
echo.

netstat -ano | findstr /R /C:":%SATELITE_API_PORT% .*LISTENING" >nul
if not errorlevel 1 (
    echo [ATENCAO] A porta %SATELITE_API_PORT% ja esta em uso.
    echo Verifique se outro processo do Satelite/API ja esta ativo.
    exit /b 1
)

:: Inicia a aplicacao Spring Boot em background controlado, sem scheduler.
:: O bloco PowerShell aguarda a porta abrir, mostra status e espera o Java encerrar.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$content = Get-Content -Raw -LiteralPath $env:START_API_SCRIPT; $parts = $content -split '(?m)^:POWERSHELL\s*$', 2; if ($parts.Count -lt 2) { throw 'Bloco PowerShell embarcado nao encontrado.' }; Invoke-Expression $parts[1]"
set "JAVA_EXIT=%ERRORLEVEL%"

echo.
if "%JAVA_EXIT%"=="0" (
    echo [INFO] O servidor foi encerrado normalmente.
) else (
    echo [ERRO] O servidor foi encerrado com falha.
)
echo Codigo de saida do Java: %JAVA_EXIT%
exit /b %JAVA_EXIT%

:POWERSHELL
$ErrorActionPreference = 'Stop'

$utf8 = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = $utf8
[Console]::InputEncoding = $utf8
$OutputEncoding = $utf8

function Write-Panel($title, [string[]] $lines) {
    Write-Host '+------------------------------------------------------+'
    Write-Host ('| ' + $title.PadRight(52) + ' |')
    Write-Host '+------------------------------------------------------+'
    foreach ($line in $lines) {
        Write-Host ('  ' + $line)
    }
    Write-Host '+------------------------------------------------------+'
}

function Test-ApiPort($port, $processId) {
    $connections = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
    return $null -ne ($connections | Where-Object { $_.OwningProcess -eq $processId } | Select-Object -First 1)
}

function Show-RecentLogLines($path, $label) {
    Write-Host ''
    Write-Host "Ultimas 20 linhas do $label ($path):"

    if ([string]::IsNullOrWhiteSpace($path) -or -not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Write-Host '  [arquivo nao encontrado]'
        return
    }

    $lines = Get-Content -LiteralPath $path -Tail 20 -ErrorAction SilentlyContinue
    if (-not $lines) {
        Write-Host '  [arquivo vazio]'
        return
    }

    foreach ($line in $lines) {
        Write-Host "  $line"
    }
}

function Show-StartupDiagnostics($errLog, $outLog) {
    Show-RecentLogLines $errLog 'start_api.err.log'
    Show-RecentLogLines $outLog 'start_api.out.log'
}

$root = $env:PROJECT_ROOT
if ([string]::IsNullOrWhiteSpace($root)) {
    $root = (Get-Location).Path
}

$port = [int]$env:SATELITE_API_PORT
$outLog = $env:START_API_OUT_LOG
$errLog = $env:START_API_ERR_LOG

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outLog) | Out-Null
Remove-Item -LiteralPath $outLog, $errLog -Force -ErrorAction SilentlyContinue

$javaArgs = @(
    '-jar',
    $env:JAR_PATH,
    '--debug=false',
    '--APP_SCHEDULER_ENABLED=false',
    '--APP_CICLO_UNICO=false',
    "--APP_PPG_ENABLED=$($env:APP_PPG_ENABLED)",
    "--APP_VEDACIT_ENABLED=$($env:APP_VEDACIT_ENABLED)",
    "--server.port=$port",
    '--spring.main.web-application-type=servlet',
    "--INTEGRATION_SCHEDULER_INTERVAL_MS=$($env:INTEGRATION_SCHEDULER_INTERVAL_MS)"
)

$process = $null

try {
    $process = Start-Process `
        -FilePath 'java' `
        -ArgumentList $javaArgs `
        -WorkingDirectory $root `
        -WindowStyle Hidden `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -PassThru

    Write-Host ("Java iniciado. PID: {0}" -f $process.Id)
    Write-Host ("Aguardando porta {0} ficar ONLINE..." -f $port)

    $online = $false
    $timeoutSeconds = 30

    for ($attempt = 1; $attempt -le $timeoutSeconds; $attempt++) {
        if ($process.HasExited) {
            $exitCode = $process.ExitCode
            if ($exitCode -eq 0) {
                $exitCode = 1
            }

            Write-Panel 'SATELITE TMS API - FALHA AO INICIAR' @(
                "PID Java saiu antes da API abrir a porta.",
                "Codigo de saida: $($process.ExitCode)",
                "Porta esperada: $port",
                "Log stdout: $outLog",
                "Log stderr: $errLog"
            )
            Show-StartupDiagnostics $errLog $outLog
            exit $exitCode
        }

        if (Test-ApiPort $port $process.Id) {
            $online = $true
            break
        }

        if (($attempt % 5) -eq 0) {
            Write-Host ("  Ainda subindo... {0}s/{1}s" -f $attempt, $timeoutSeconds)
        }

        Start-Sleep -Seconds 1
        $process.Refresh()
    }

    if (-not $online) {
        Write-Panel 'SATELITE TMS API - SEM CONFIRMACAO' @(
            "Timeout de $timeoutSeconds segundos aguardando LISTENING na porta $port.",
            "O Java sera encerrado para evitar loop infinito.",
            "PID Java: $($process.Id)",
            "Log stdout: $outLog",
            "Log stderr: $errLog"
        )
        Show-StartupDiagnostics $errLog $outLog
        exit 124
    } else {
        Clear-Host
        Write-Panel 'SATELITE TMS API - ONLINE' @(
            "Status    : API no ar",
            "URL       : http://127.0.0.1:$port",
            "Porta     : $port LISTENING",
            "PID Java  : $($process.Id)",
            "Modo      : somente API",
            "Scheduler : DESATIVADO",
            "Logs      : $outLog",
            "Erros     : $errLog"
        )
        Write-Host ''
        Write-Host 'Esta janela esta monitorando o processo.'
        Write-Host 'Se o Java cair, o start_api.bat encerra com diagnostico.'
        Write-Host 'Pressione CTRL+C para parar o servidor.'
    }

    Wait-Process -Id $process.Id
    $process.Refresh()

    if ($process.ExitCode -ne 0) {
        Write-Panel 'SATELITE TMS API - ENCERRADA COM ERRO' @(
            "Codigo de saida: $($process.ExitCode)",
            "PID Java: $($process.Id)",
            "Log stdout: $outLog",
            "Log stderr: $errLog"
        )
        Show-StartupDiagnostics $errLog $outLog
    }

    exit $process.ExitCode
} catch {
    Write-Panel 'SATELITE TMS API - FALHA NO STARTER' @(
        $_.Exception.Message,
        "Log stdout: $outLog",
        "Log stderr: $errLog"
    )
    Show-StartupDiagnostics $errLog $outLog
    exit 1
} finally {
    if ($process -and -not $process.HasExited) {
        try {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        } catch {
        }
    }
}
