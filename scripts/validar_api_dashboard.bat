@echo off
setlocal EnableExtensions

set "PORTA=19090"

powershell -NoProfile -Command "$conexao = Get-NetTCPConnection -State Listen -LocalPort %PORTA% -ErrorAction SilentlyContinue | Select-Object -First 1; if ($null -eq $conexao) { exit 0 }; $processo = Get-Process -Id $conexao.OwningProcess -ErrorAction SilentlyContinue; Write-Host ('[ERRO] Porta %PORTA% ocupada por PID {0} ({1}).' -f $conexao.OwningProcess, $processo.ProcessName); exit 2"
if errorlevel 2 exit /b 2
if errorlevel 1 (
    echo [ERRO] Nao foi possivel verificar a porta %PORTA%.
    exit /b 1
)

echo [OK] Porta %PORTA% livre para Satelite-API-19090.
exit /b 0
