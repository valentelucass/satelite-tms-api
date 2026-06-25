@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat" require-jar validate-db
if errorlevel 1 exit /b 1

set "SATELITE_MODO=%~1"
set "APP_VEDACIT_ENABLED=%~2"
set "APP_PPG_ENABLED=%~3"
set "LOG_FILE=%~4"
set "APP_SCHEDULER_ENABLED=%~5"

if "%SATELITE_MODO%"=="" set "SATELITE_MODO=Loop 15 min Vedacit + PPG"
if "%APP_VEDACIT_ENABLED%"=="" set "APP_VEDACIT_ENABLED=true"
if "%APP_PPG_ENABLED%"=="" set "APP_PPG_ENABLED=true"
if "%LOG_FILE%"=="" set "LOG_FILE=logs\satelite_background.log"
if "%APP_SCHEDULER_ENABLED%"=="" set "APP_SCHEDULER_ENABLED=true"

for %%I in ("%LOG_FILE%") do set "LOG_FILE_ABS=%%~fI"
if not exist "%LOGS_DIR%" mkdir "%LOGS_DIR%" >nul 2>&1

call :obter_pid_porta_api
if defined PORTA_API_PID (
    echo [ERRO] Porta %PORTA_API% ocupada pelo PID %PORTA_API_PID%.
    echo Use a opcao 8 para parar o robo antes de iniciar novamente.
    exit /b 1
)

set "JAVA_LAUNCHER=javaw"
where javaw.exe >nul 2>&1
if errorlevel 1 set "JAVA_LAUNCHER=java"

echo Iniciando modo [%SATELITE_MODO%] em background...
echo Porta: %PORTA_API%
echo Scheduler habilitado: %APP_SCHEDULER_ENABLED%
if /I "%APP_SCHEDULER_ENABLED%"=="true" echo Intervalo do loop: 15 min
echo Log: %LOG_FILE_ABS%

START "Satelite %SATELITE_MODO%" /MIN "%JAVA_LAUNCHER%" -jar "%JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=%APP_SCHEDULER_ENABLED%" "--APP_CICLO_UNICO=false" "--APP_PPG_ENABLED=%APP_PPG_ENABLED%" "--APP_VEDACIT_ENABLED=%APP_VEDACIT_ENABLED%" "--server.port=%PORTA_API%" "--spring.main.web-application-type=servlet" "--INTEGRATION_SCHEDULER_INTERVAL_MS=%BACKGROUND_INTERVAL_MS%" 1^> "%LOG_FILE_ABS%" 2^>^&1
if errorlevel 1 (
    echo [ERRO] Falha ao iniciar em background.
    exit /b 1
)

echo Aguardando inicializacao por ate 60 segundos...
call :aguardar_porta_api 60
if errorlevel 1 (
    echo [ERRO] API nao confirmou a porta %PORTA_API%.
    echo Log: %LOG_FILE_ABS%
    echo Se houver processo pendurado, use scripts\parar.bat ou a opcao 8 do menu.
    exit /b 1
)

echo [SUCESSO] Satelite TMS iniciado na porta %PORTA_API%.
echo O processo usa %JAVA_LAUNCHER% e permanece ativo mesmo se este CMD for fechado.
echo Redirecionando para os logs ao vivo em 3 segundos...
timeout /t 3 /nobreak >nul
call "%~dp0logs.bat"
exit /b 0

:obter_pid_porta_api
set "PORTA_API_PID="
for /f "tokens=5" %%P in ('netstat -aon ^| findstr /R /C:":%PORTA_API% .*LISTENING"') do (
    set "PORTA_API_PID=%%P"
)
exit /b 0

:aguardar_porta_api
set "TENTATIVAS=%~1"
if "%TENTATIVAS%"=="" set "TENTATIVAS=60"

:aguardar_porta_api_loop
call :obter_pid_porta_api
if defined PORTA_API_PID exit /b 0
if "%TENTATIVAS%"=="0" exit /b 1
set /a TENTATIVAS-=1
timeout /t 1 /nobreak >nul
goto aguardar_porta_api_loop
