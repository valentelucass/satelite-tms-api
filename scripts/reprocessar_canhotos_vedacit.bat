@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat" require-jar validate-db
if errorlevel 1 exit /b 1

echo.
echo =========================================================
echo  Reprocessar Pendencias Tecnicas de Canhoto Vedacit
echo =========================================================
echo Consulta somente falhas tecnicas recuperaveis com XML confirmado.
echo Recusas definitivas e timeouts ambiguos nao sao reenviados aqui.
echo O canhoto e obtido exclusivamente do SFTP; nao usa fallback ESL.
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
echo Iniciando reprocessamento tecnico SFTP de todos os canhotos elegiveis, ate %RETRY_LIMIT% por execucao...
"%JAVA_EXECUTABLE%" -jar "%JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=false" "--APP_CICLO_UNICO=false" "--APP_ETL_REPESCAGEM_ENABLED=false" "--APP_PPG_ENABLED=false" "--APP_VEDACIT_ENABLED=false" "--APP_SELIA_ENABLED=false" "--APP_SUPPORTE_ENABLED=false" "--server.port=0" "--spring.main.web-application-type=none" "--VEDACIT_SFTP_RECEIPT_ONLY=true" "--VEDACIT_SFTP_RECONCILIATION_ENABLED=true" "--vedacit.receipt-retry.enabled=true" "--vedacit.receipt-retry.max-items=%RETRY_LIMIT%"
set "JAVA_EXIT=%ERRORLEVEL%"

if "%JAVA_EXIT%"=="0" echo [SUCESSO] Reprocessamento isolado finalizado.
if not "%JAVA_EXIT%"=="0" echo [ERRO] Reprocessamento isolado finalizado com codigo %JAVA_EXIT%.
exit /b %JAVA_EXIT%
