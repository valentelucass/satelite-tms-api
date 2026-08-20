@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat" require-jar validate-db
if errorlevel 1 exit /b 1

echo.
echo =========================================================
echo  Reprocessar Pendencias Tecnicas Vedacit
echo =========================================================
echo Trata somente XML/Canhoto com falha tecnica recuperavel.
echo Timeouts ambiguos ficam exclusivamente na opcao P controlada.
echo Para canhotos, usa apenas SFTP; nao consulta fallback ESL.
echo.
set "RETRY_LIMIT=%~1"
if not defined RETRY_LIMIT set "RETRY_LIMIT=500"

powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $n = [int]'%RETRY_LIMIT%'; if ($n -lt 1 -or $n -gt 500) { exit 2 }; exit 0 } catch { exit 1 }"
if errorlevel 2 (
    echo [ERRO] Informe uma quantidade entre 1 e 500.
    exit /b 1
)
if errorlevel 1 (
    echo [ERRO] Informe uma quantidade inteira.
    exit /b 1
)

echo.
echo Iniciando repescagem tecnica de todos os registros elegiveis, ate %RETRY_LIMIT% por execucao...
"%JAVA_EXECUTABLE%" -jar "%JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=false" "--APP_NIGHTLY_RETRY_ENABLED=false" "--APP_CICLO_UNICO=false" "--APP_ETL_REPESCAGEM_ENABLED=false" "--APP_PPG_ENABLED=false" "--APP_VEDACIT_ENABLED=false" "--APP_SELIA_ENABLED=false" "--APP_SUPPORTE_ENABLED=false" "--server.port=0" "--spring.main.web-application-type=none" "--VEDACIT_SFTP_RECEIPT_ONLY=true" "--VEDACIT_SFTP_RECONCILIATION_ENABLED=true" "--vedacit.nightly-retry.run-on-start=true" "--vedacit.nightly-retry.max-items=%RETRY_LIMIT%" "--vedacit.nightly-retry.max-attempts=5"
set "JAVA_EXIT=%ERRORLEVEL%"

if "%JAVA_EXIT%"=="0" echo [SUCESSO] Repescagem tecnica isolada finalizada.
if not "%JAVA_EXIT%"=="0" echo [ERRO] Repescagem tecnica isolada finalizada com codigo %JAVA_EXIT%.
exit /b %JAVA_EXIT%
