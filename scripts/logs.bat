@echo off
setlocal EnableExtensions
if not "%~2"=="" goto uso
if "%~1"=="" goto escolher
if /I "%~1"=="api-integracoes-19090" goto api
if /I "%~1"=="clientes" goto clientes
:uso
echo Uso: logs.bat [api-integracoes-19090^|clientes]
exit /b 2
:escolher
choice /c 12 /n /m "Logs do Satelite: 1 API, 2 Clientes: "
if errorlevel 2 goto clientes
:api
set "SATELITE_LOG_ATUAL=%~dp0..\logs-residentes\api-integracoes\atual.log"
goto acompanhar
:clientes
set "SATELITE_LOG_ATUAL=%~dp0..\logs-residentes\clientes\atual.log"
:acompanhar
if not exist "%SATELITE_LOG_ATUAL%" (
    echo O processo ainda nao gravou seu log em "%SATELITE_LOG_ATUAL%".
    exit /b 1
)
echo Ctrl+C encerra a leitura.
powershell.exe -NoProfile -Command "Get-Content -LiteralPath $env:SATELITE_LOG_ATUAL -Encoding UTF8 -Tail 50 -Wait"
exit /b %errorlevel%
