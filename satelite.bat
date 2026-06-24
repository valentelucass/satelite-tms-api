@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0"

set "ALLOWED_DB_NAME=SATELITE_TMS_AUDITORIA"
set "JAR_PATH=target\satelite-0.0.1-SNAPSHOT.jar"
set "PORTA_API=8080"

:menu
cls
echo.
echo  SATELITE TMS
echo.
echo  =============================================================
echo                    SATELITE TMS CONTROL CENTER
echo  =============================================================
echo.
echo  1. Iniciar [Vedacit + PPG] - Background
echo  2. Iniciar [Apenas Vedacit] - Background
echo  3. Iniciar [Apenas PPG] - Background
echo  4. Iniciar [API Servidor - Sem robo] - Background
echo  5. Acompanhar Logs ao Vivo (Tempo Real)
echo  6. Status da porta 8080
echo  7. Parar API / Robo
echo  8. Executar Ciclo Unico - Foreground
echo  9. Testes E2E (PPG/Vedacit)
echo  A. Carga Retroativa - Foreground
echo  0. Sair
echo.
choice /c 123456789A0 /n /m "Escolha uma opcao: "

if errorlevel 11 goto sair
if errorlevel 10 goto carga_retroativa
if errorlevel 9 goto testes_e2e
if errorlevel 8 goto ciclo_unico
if errorlevel 7 goto matar_processos
if errorlevel 6 goto processos
if errorlevel 5 goto tail_logs
if errorlevel 4 goto iniciar_api
if errorlevel 3 goto iniciar_ppg
if errorlevel 2 goto iniciar_vedacit
if errorlevel 1 goto iniciar_todos

:iniciar_todos
cls
call :iniciar_background "Vedacit + PPG" "true" "true" "true" "logs\satelite_vedacit_ppg.log"
goto pausar_menu

:iniciar_vedacit
cls
call :iniciar_background "Apenas Vedacit" "false" "true" "true" "logs\satelite_vedacit.log"
goto pausar_menu

:iniciar_ppg
cls
call :iniciar_background "Apenas PPG" "true" "false" "true" "logs\satelite_ppg.log"
goto pausar_menu

:iniciar_api
cls
call :iniciar_background "API Servidor (Sem robo)" "false" "false" "false" "logs\satelite_api.log"
goto pausar_menu

:iniciar_background
set "SATELITE_MODO=%~1"
set "SATELITE_APP_PPG_OVERRIDE=%~2"
set "SATELITE_APP_VEDACIT_OVERRIDE=%~3"
set "SATELITE_SCHEDULER_OVERRIDE=%~4"
set "LOG_FILE=%~5"
set "SATELITE_CICLO_UNICO_OVERRIDE=false"
set "SATELITE_SERVER_PORT_OVERRIDE=%PORTA_API%"
set "SATELITE_INTERVAL_OVERRIDE="
set "SATELITE_EXTRA_ARGS_OVERRIDE="

echo Iniciando modo [%SATELITE_MODO%]...
call :preparar_execucao
if errorlevel 1 exit /b 1

call :criar_pasta_logs
if errorlevel 1 exit /b 1

call :obter_pid_porta_api
if defined PORTA_API_PID (
    echo [ERRO] Porta %PORTA_API% ocupada pelo PID %PORTA_API_PID%.
    echo Use a opcao Parar antes de iniciar novamente.
    exit /b 1
)

echo Aguardando inicializacao...
START "Satelite %SATELITE_MODO%" /B cmd /c java -jar "%JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=%APP_SCHEDULER_ENABLED%" "--APP_CICLO_UNICO=%APP_CICLO_UNICO%" "--APP_PPG_ENABLED=%APP_PPG_ENABLED%" "--APP_VEDACIT_ENABLED=%APP_VEDACIT_ENABLED%" "--server.port=%SERVER_PORT%" %SPRING_EXTRA_ARGS% 1^> "%LOG_FILE%" 2^>^&1
if errorlevel 1 (
    echo [ERRO] Falha ao iniciar em background.
    exit /b 1
)

call :aguardar_porta_api 30
if errorlevel 1 (
    echo [ERRO] API nao confirmou a porta %PORTA_API%.
    echo Log: %LOG_FILE%
    exit /b 1
)

echo [SUCESSO] Satelite TMS iniciado na porta %PORTA_API%.
echo Log: %LOG_FILE%
echo Redirecionando para os logs ao vivo em 3 segundos...
set "TAIL_LOG_FILE_OVERRIDE=%LOG_FILE%"
timeout /t 3 /nobreak >nul
goto tail_logs

:ciclo_unico
cls
set "SATELITE_MODO=Ciclo unico operacional"
set "SATELITE_SCHEDULER_OVERRIDE=false"
set "SATELITE_CICLO_UNICO_OVERRIDE=true"
set "SATELITE_SERVER_PORT_OVERRIDE=0"
set "SATELITE_INTERVAL_OVERRIDE="
set "SATELITE_APP_PPG_OVERRIDE=true"
set "SATELITE_APP_VEDACIT_OVERRIDE=true"
set "SATELITE_EXTRA_ARGS_OVERRIDE="
call :rodar_foreground
goto pausar_menu

:carga_retroativa
cls
call :titulo "Carga Retroativa"
echo Informe as datas no formato AAAA-MM-DD.
echo.
set "RETRO_START="
set /p "RETRO_START=Data inicial: "
set "RETRO_END="
set /p "RETRO_END=Data final: "
call :validar_periodo_retroativo "%RETRO_START%" "%RETRO_END%"
if errorlevel 1 goto pausar_menu
echo.
echo  1. Vedacit
echo  2. PPG
echo  3. Todos
echo.
choice /c 123 /n /m "Destino: "
if errorlevel 3 set "RETRO_DESTINO=TODOS"
if errorlevel 2 set "RETRO_DESTINO=PPG"
if errorlevel 1 set "RETRO_DESTINO=VEDACIT"

set "SATELITE_MODO=Carga retroativa %RETRO_DESTINO% [%RETRO_START% ate %RETRO_END%]"
set "SATELITE_SCHEDULER_OVERRIDE=false"
set "SATELITE_CICLO_UNICO_OVERRIDE=false"
set "SATELITE_SERVER_PORT_OVERRIDE=0"
set "SATELITE_INTERVAL_OVERRIDE="
set "SATELITE_APP_PPG_OVERRIDE=true"
set "SATELITE_APP_VEDACIT_OVERRIDE=true"
if /I "%RETRO_DESTINO%"=="VEDACIT" set "SATELITE_APP_PPG_OVERRIDE=false"
if /I "%RETRO_DESTINO%"=="PPG" set "SATELITE_APP_VEDACIT_OVERRIDE=false"
set "SATELITE_EXTRA_ARGS_OVERRIDE=--retroactive.enabled=true --retroactive.start=%RETRO_START% --retroactive.end=%RETRO_END% --retroactive.destino=%RETRO_DESTINO%"
call :rodar_foreground
goto pausar_menu

:preparar_execucao
call :carregar_env
if not "%SATELITE_SCHEDULER_OVERRIDE%"=="" set "APP_SCHEDULER_ENABLED=%SATELITE_SCHEDULER_OVERRIDE%"
if not "%SATELITE_CICLO_UNICO_OVERRIDE%"=="" set "APP_CICLO_UNICO=%SATELITE_CICLO_UNICO_OVERRIDE%"
if not "%SATELITE_SERVER_PORT_OVERRIDE%"=="" set "SERVER_PORT=%SATELITE_SERVER_PORT_OVERRIDE%"
if not "%SATELITE_APP_PPG_OVERRIDE%"=="" set "APP_PPG_ENABLED=%SATELITE_APP_PPG_OVERRIDE%"
if not "%SATELITE_APP_VEDACIT_OVERRIDE%"=="" set "APP_VEDACIT_ENABLED=%SATELITE_APP_VEDACIT_OVERRIDE%"
if "%DB_NAME%"=="" set "DB_NAME=%ALLOWED_DB_NAME%"
if "%SATELITE_DB_URL%"=="" set "SATELITE_DB_URL=%DB_URL%"
if "%SATELITE_DB_USER%"=="" set "SATELITE_DB_USER=%DB_USER%"
if "%SATELITE_DB_PASSWORD%"=="" set "SATELITE_DB_PASSWORD=%DB_PASSWORD%"
if "%APP_SCHEDULER_ENABLED%"=="" set "APP_SCHEDULER_ENABLED=true"
if "%APP_CICLO_UNICO%"=="" set "APP_CICLO_UNICO=false"
if "%APP_PPG_ENABLED%"=="" set "APP_PPG_ENABLED=true"
if "%APP_VEDACIT_ENABLED%"=="" set "APP_VEDACIT_ENABLED=true"
if "%SERVER_PORT%"=="" set "SERVER_PORT=%PORTA_API%"
call :validar_database_permitida
if errorlevel 1 exit /b 1

if not exist "%JAR_PATH%" (
    echo.
    echo [ERRO] JAR nao encontrado: %JAR_PATH%
    echo Gere o artefato manualmente antes de iniciar o robo.
    exit /b 1
)

set "SPRING_EXTRA_ARGS="
if not "%SATELITE_INTERVAL_OVERRIDE%"=="" set "SPRING_EXTRA_ARGS=%SPRING_EXTRA_ARGS% --INTEGRATION_SCHEDULER_INTERVAL_MS=%SATELITE_INTERVAL_OVERRIDE%"
if not "%SATELITE_EXTRA_ARGS_OVERRIDE%"=="" set "SPRING_EXTRA_ARGS=%SPRING_EXTRA_ARGS% %SATELITE_EXTRA_ARGS_OVERRIDE%"

exit /b 0

:validar_periodo_retroativo
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $ci = [Globalization.CultureInfo]::InvariantCulture; $inicio = [datetime]::ParseExact('%~1', 'yyyy-MM-dd', $ci, [Globalization.DateTimeStyles]::None); $fim = [datetime]::ParseExact('%~2', 'yyyy-MM-dd', $ci, [Globalization.DateTimeStyles]::None); if ($fim -lt $inicio) { exit 2 }; exit 0 } catch { exit 1 }"
set "RETRO_VALIDACAO_EXIT=%ERRORLEVEL%"
if "%RETRO_VALIDACAO_EXIT%"=="1" (
    echo.
    echo [ERRO] Datas invalidas. Use o formato AAAA-MM-DD. Exemplo: 2026-05-01
    exit /b 1
)
if "%RETRO_VALIDACAO_EXIT%"=="2" (
    echo.
    echo [ERRO] Data final nao pode ser anterior a data inicial.
    exit /b 1
)
exit /b 0

:rodar_foreground
echo Iniciando modo [%SATELITE_MODO%]...
call :preparar_execucao
if errorlevel 1 exit /b 1

echo.
echo A JVM sera encerrada ao finalizar.
echo.

java -jar "%JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=%APP_SCHEDULER_ENABLED%" "--APP_CICLO_UNICO=%APP_CICLO_UNICO%" "--APP_PPG_ENABLED=%APP_PPG_ENABLED%" "--APP_VEDACIT_ENABLED=%APP_VEDACIT_ENABLED%" "--server.port=%SERVER_PORT%" %SPRING_EXTRA_ARGS%
set "JAVA_EXIT=%ERRORLEVEL%"

if "%JAVA_EXIT%"=="0" echo [SUCESSO] Processo finalizado.
if not "%JAVA_EXIT%"=="0" echo [ERRO] Processo finalizado com codigo %JAVA_EXIT%.
exit /b %JAVA_EXIT%

:criar_pasta_logs
if not exist "logs" (
    mkdir "logs"
    if errorlevel 1 (
        echo.
        echo [ERRO] Nao foi possivel criar a pasta logs.
        exit /b 1
    )
)
exit /b 0

:tail_logs
cls
echo Exibindo logs em tempo real.
echo Pressione a tecla Q para sair do log e retornar ao menu.
echo.

set "TAIL_LOG_FILE="
if defined TAIL_LOG_FILE_OVERRIDE (
    set "TAIL_LOG_FILE=%TAIL_LOG_FILE_OVERRIDE%"
    set "TAIL_LOG_FILE_OVERRIDE="
)

if defined TAIL_LOG_FILE if not exist "%TAIL_LOG_FILE%" set "TAIL_LOG_FILE="
if not defined TAIL_LOG_FILE if exist "logs\satelite_vedacit_ppg.log" set "TAIL_LOG_FILE=logs\satelite_vedacit_ppg.log"
if not defined TAIL_LOG_FILE if exist "logs\satelite_vedacit.log" set "TAIL_LOG_FILE=logs\satelite_vedacit.log"
if not defined TAIL_LOG_FILE if exist "logs\satelite_ppg.log" set "TAIL_LOG_FILE=logs\satelite_ppg.log"
if not defined TAIL_LOG_FILE if exist "logs\satelite_api.log" set "TAIL_LOG_FILE=logs\satelite_api.log"

if "%TAIL_LOG_FILE%"=="" (
    echo Nenhum log encontrado. Inicie o robo primeiro.
    echo.
    pause
    goto menu
)

echo Arquivo: %TAIL_LOG_FILE%
echo.
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; $file='%TAIL_LOG_FILE%'; if (Test-Path $file) { Write-Host '=== Monitorando logs em tempo real ===' -ForegroundColor Cyan; Write-Host '>>> Pressione a tecla [Q] para parar e voltar ao menu principal.' -ForegroundColor Yellow; Write-Host ''; $stream = [System.IO.File]::Open($file, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite); $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8); while($true) { if ([Console]::KeyAvailable) { if ([Console]::ReadKey($true).Key -eq 'Q') { break } }; $line = $reader.ReadLine(); if ($line -ne $null) { Write-Host $line } else { Start-Sleep -Milliseconds 200 } }; $reader.Close(); $stream.Close(); }"
goto menu

:processos
cls
echo Status da API Satelite
echo.
call :obter_pid_porta_api
if defined PORTA_API_PID (
    echo Porta  PID
    echo %PORTA_API%   %PORTA_API_PID%
) else (
    echo Nenhum processo ouvindo na porta %PORTA_API%.
)
goto pausar_menu

:matar_processos
cls
call :parar_processos_satelite
goto pausar_menu

:testes_e2e
cls
call :titulo "Testes E2E"
echo  1. Teste E2E PPG - Login + Ocorrencia
echo  2. Teste de Producao VEDACIT - Ciclo Unico Isolado
echo  3. Voltar
echo.
choice /c 123 /n /m "Escolha uma opcao: "
if errorlevel 3 goto menu
if errorlevel 2 goto teste_vedacit_producao
if errorlevel 1 goto teste_ppg_e2e

:teste_ppg_e2e
cls
call :titulo "Teste E2E PPG - Login + Ocorrencia"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test_ppg_e2e.ps1
goto pausar_menu

:teste_vedacit_producao
cls
call :titulo "Teste de Producao VEDACIT - Ciclo Unico Isolado"
set "SATELITE_MODO=Teste de Producao VEDACIT - Ciclo Unico Isolado"
set "SATELITE_SCHEDULER_OVERRIDE=false"
set "SATELITE_CICLO_UNICO_OVERRIDE=true"
set "SATELITE_SERVER_PORT_OVERRIDE=0"
set "SATELITE_INTERVAL_OVERRIDE="
set "SATELITE_APP_PPG_OVERRIDE=false"
set "SATELITE_APP_VEDACIT_OVERRIDE=true"
set "SATELITE_EXTRA_ARGS_OVERRIDE="
call :rodar_foreground
goto pausar_menu

:carregar_env
set "ENV_FILE=%~dp0.env"
if exist "%ENV_FILE%" (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
        if not "%%A"=="" set "%%A=%%B"
    )
)
exit /b 0

:validar_database_permitida
if /I not "%DB_NAME%"=="%ALLOWED_DB_NAME%" (
    echo.
    echo [ERRO] Database nao permitida: %DB_NAME%
    echo Este repositorio so pode operar na database %ALLOWED_DB_NAME%.
    exit /b 1
)

if not "%SATELITE_DB_URL%"=="" (
    echo "%SATELITE_DB_URL%" | findstr /I /C:"databaseName=%ALLOWED_DB_NAME%" >nul
    if errorlevel 1 (
        echo.
        echo [ERRO] SATELITE_DB_URL nao aponta para %ALLOWED_DB_NAME%.
        echo Ajuste o .env antes de rodar o Satelite TMS.
        exit /b 1
    )
)
exit /b 0

:obter_pid_porta_api
set "PORTA_API_PID="
for /f "tokens=5" %%P in ('netstat -aon ^| findstr :8080 ^| findstr LISTENING') do (
    set "PORTA_API_PID=%%P"
)
exit /b 0

:parar_processos_satelite
echo Parando API / Robo...
call :obter_pid_porta_api

if defined PORTA_API_PID (
    taskkill /F /PID %PORTA_API_PID% >nul 2>&1
    call :encerrar_zumbis_satelite
    call :aguardar_porta_liberada 10
    if errorlevel 1 (
        echo [ERRO] A porta %PORTA_API% continua ocupada.
        exit /b 1
    )

    echo [SUCESSO] Processo %PORTA_API_PID% encerrado e porta liberada.
    exit /b 0
)

call :encerrar_zumbis_satelite
call :obter_pid_porta_api
if defined PORTA_API_PID (
    taskkill /F /PID %PORTA_API_PID% >nul 2>&1
    call :aguardar_porta_liberada 10
    if errorlevel 1 (
        echo [ERRO] A porta %PORTA_API% continua ocupada.
        exit /b 1
    )

    echo [SUCESSO] Processo %PORTA_API_PID% encerrado e porta liberada.
    exit /b 0
)

echo Nenhum processo detectado.
exit /b 0

:encerrar_zumbis_satelite
wmic process where "name='java.exe' and commandline like '%%satelite%%'" call terminate >nul 2>&1
wmic process where "name='javaw.exe' and commandline like '%%satelite%%'" call terminate >nul 2>&1
exit /b 0

:aguardar_porta_api
set "TENTATIVAS=%~1"
if "%TENTATIVAS%"=="" set "TENTATIVAS=30"

:aguardar_porta_api_loop
call :obter_pid_porta_api
if defined PORTA_API_PID exit /b 0
if "%TENTATIVAS%"=="0" exit /b 1
set /a TENTATIVAS-=1
timeout /t 1 /nobreak >nul
goto aguardar_porta_api_loop

:aguardar_porta_liberada
set "TENTATIVAS=%~1"
if "%TENTATIVAS%"=="" set "TENTATIVAS=10"

:aguardar_porta_liberada_loop
call :obter_pid_porta_api
if not defined PORTA_API_PID exit /b 0
if "%TENTATIVAS%"=="0" exit /b 1
set /a TENTATIVAS-=1
timeout /t 1 /nobreak >nul
goto aguardar_porta_liberada_loop

:titulo
echo.
echo =========================================================
echo  %~1
echo =========================================================
echo.
exit /b 0

:pausar_menu
echo.
pause
goto menu

:sair
cls
echo.
echo Encerrando.
exit /b 0
