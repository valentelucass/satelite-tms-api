@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat" require-jar validate-db
if errorlevel 1 exit /b 1

echo.
echo =========================================================
echo  Recuperacao dirigida de XML Vedacit por NF-e
echo =========================================================
echo Informe um arquivo TXT com uma chave NF-e por linha.
echo A rotina consulta apenas a ocorrencia 110, nao envia canhoto,
echo nao avanca cursor e preserva a idempotencia por CT-e.
echo.
set /p "NFE_FILE=Arquivo TXT: "
if "%NFE_FILE%"=="" (
    echo [ERRO] Arquivo de NF-es obrigatorio.
    exit /b 1
)
set "RECOVERY_LIMIT=50"
set /p "RECOVERY_LIMIT=Quantidade maxima [50]: "
if "%RECOVERY_LIMIT%"=="" set "RECOVERY_LIMIT=50"

powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $n = [int]'%RECOVERY_LIMIT%'; if ($n -lt 1 -or $n -gt 200) { exit 2 }; exit 0 } catch { exit 1 }"
if errorlevel 2 (
    echo [ERRO] Informe uma quantidade entre 1 e 200.
    exit /b 1
)
if errorlevel 1 (
    echo [ERRO] Informe uma quantidade inteira.
    exit /b 1
)

"%JAVA_EXECUTABLE%" -jar "%JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=false" "--APP_CICLO_UNICO=false" "--APP_ETL_REPESCAGEM_ENABLED=false" "--APP_PPG_ENABLED=false" "--APP_VEDACIT_ENABLED=false" "--APP_SELIA_ENABLED=false" "--APP_SUPPORTE_ENABLED=false" "--server.port=0" "--spring.main.web-application-type=none" "--vedacit.recovery.enabled=true" "--vedacit.recovery.nfe-file=%NFE_FILE%" "--vedacit.recovery.max-items=%RECOVERY_LIMIT%"
set "JAVA_EXIT=%ERRORLEVEL%"

if "%JAVA_EXIT%"=="0" echo [SUCESSO] Recuperacao dirigida finalizada.
if not "%JAVA_EXIT%"=="0" echo [ERRO] Recuperacao dirigida finalizada com codigo %JAVA_EXIT%.
exit /b %JAVA_EXIT%
