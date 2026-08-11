@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat" require-jar validate-db
if errorlevel 1 exit /b 1

call :titulo "Carga Retroativa"
echo Informe as datas no formato AAAA-MM-DD.
echo.
set "RETRO_START="
set /p "RETRO_START=Data inicial: "
set "RETRO_END="
set /p "RETRO_END=Data final: "

call :validar_periodo_retroativo "%RETRO_START%" "%RETRO_END%"
if errorlevel 1 exit /b 1

echo.
echo  1. Vedacit
echo  2. PPG
echo  3. SELIA
echo  4. Todos (Vedacit + PPG + SELIA)
echo.
choice /c 1234 /n /m "Destino: "
set "RETRO_OPCAO=%ERRORLEVEL%"
if "%RETRO_OPCAO%"=="1" set "RETRO_DESTINO=VEDACIT"
if "%RETRO_OPCAO%"=="2" set "RETRO_DESTINO=PPG"
if "%RETRO_OPCAO%"=="3" set "RETRO_DESTINO=SELIA"
if "%RETRO_OPCAO%"=="4" set "RETRO_DESTINO=TODOS"

set "APP_VEDACIT_ENABLED=false"
set "APP_PPG_ENABLED=false"
set "APP_SELIA_ENABLED=false"
if /I "%RETRO_DESTINO%"=="VEDACIT" set "APP_VEDACIT_ENABLED=true"
if /I "%RETRO_DESTINO%"=="PPG" set "APP_PPG_ENABLED=true"
if /I "%RETRO_DESTINO%"=="SELIA" set "APP_SELIA_ENABLED=true"
if /I "%RETRO_DESTINO%"=="TODOS" set "APP_VEDACIT_ENABLED=true"
if /I "%RETRO_DESTINO%"=="TODOS" set "APP_PPG_ENABLED=true"
if /I "%RETRO_DESTINO%"=="TODOS" set "APP_SELIA_ENABLED=true"

echo.
echo Iniciando carga retroativa %RETRO_DESTINO% [%RETRO_START% ate %RETRO_END%]...
echo Modo trabalhador: sem Tomcat, sem disputa pela porta %PORTA_API%.
echo.

"%JAVA_EXECUTABLE%" -jar "%JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=false" "--APP_CICLO_UNICO=false" "--APP_ETL_REPESCAGEM_ENABLED=false" "--APP_PPG_ENABLED=%APP_PPG_ENABLED%" "--APP_VEDACIT_ENABLED=%APP_VEDACIT_ENABLED%" "--APP_SELIA_ENABLED=%APP_SELIA_ENABLED%" "--SELIA_NFE_WHITELIST_ENABLED=false" "--server.port=0" "--spring.main.web-application-type=none" "--retroactive.enabled=true" "--retroactive.start=%RETRO_START%" "--retroactive.end=%RETRO_END%" "--retroactive.destino=%RETRO_DESTINO%"
set "JAVA_EXIT=%ERRORLEVEL%"

if "%JAVA_EXIT%"=="0" echo [SUCESSO] Carga retroativa finalizada.
if not "%JAVA_EXIT%"=="0" echo [ERRO] Carga retroativa finalizada com codigo %JAVA_EXIT%.
exit /b %JAVA_EXIT%

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

:titulo
echo.
echo =========================================================
echo  %~1
echo =========================================================
echo.
exit /b 0
