@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat" require-jar validate-db
if errorlevel 1 exit /b 1

set "EXECUTABLE_JAR_PATH=%JAR_PATH%"
if exist "%PROJECT_ROOT%\target\satelite-sftp-monitor.jar" set "EXECUTABLE_JAR_PATH=%PROJECT_ROOT%\target\satelite-sftp-monitor.jar"
if exist "%PROJECT_ROOT%\target\satelite-0.0.1-SNAPSHOT-vedacit.jar" set "EXECUTABLE_JAR_PATH=%PROJECT_ROOT%\target\satelite-0.0.1-SNAPSHOT-vedacit.jar"
if exist "%PROJECT_ROOT%\target\satelite-0.0.1-SNAPSHOT-vedacit-fix.jar" set "EXECUTABLE_JAR_PATH=%PROJECT_ROOT%\target\satelite-0.0.1-SNAPSHOT-vedacit-fix.jar"

echo.
echo =========================================================
echo  Lote SFTP de Canhotos Vedacit Pendentes
echo =========================================================
echo Seleciona somente PENDENTE_FOTO com XML ja confirmado e CT-e
echo auditado. Le exclusivamente o SFTP; nao consulta fallback ESL
echo e nao reenvia XML de CT-e.
echo.
set "BATCH_LIMIT=%~1"
if not defined BATCH_LIMIT set "BATCH_LIMIT=100"

powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $n = [int]'%BATCH_LIMIT%'; if ($n -lt 1 -or $n -gt 100) { exit 2 }; exit 0 } catch { exit 1 }"
if errorlevel 2 (
    echo [ERRO] Informe uma quantidade entre 1 e 100.
    exit /b 1
)
if errorlevel 1 (
    echo [ERRO] Informe uma quantidade inteira.
    exit /b 1
)

echo.
echo Iniciando dreno SFTP de todos os candidatos, em lotes de ate %BATCH_LIMIT% canhoto(s)...
set "DRAIN_ARGUMENT=--vedacit.sftp-receipt-batch.drain-until-idle=true --vedacit.sftp-receipt-batch.drain-max-rounds=50 --vedacit.sftp-receipt-batch.drain-between-rounds-ms=30000"
"%JAVA_EXECUTABLE%" -jar "%EXECUTABLE_JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=false" "--APP_CICLO_UNICO=false" "--APP_ETL_REPESCAGEM_ENABLED=false" "--APP_ETL_PENDENCIAS_ENABLED=false" "--APP_PPG_ENABLED=false" "--APP_VEDACIT_ENABLED=false" "--APP_SELIA_ENABLED=false" "--APP_SUPPORTE_ENABLED=false" "--server.port=0" "--spring.main.web-application-type=none" "--SFTP_RODOGARCIA_ENABLED=true" "--VEDACIT_SFTP_RECEIPT_ONLY=true" "--vedacit.sftp-receipt-batch.enabled=true" "--vedacit.sftp-receipt-batch.max-items=%BATCH_LIMIT%" "--vedacit.sftp-receipt-batch.interval-ms=1000" %DRAIN_ARGUMENT%
set "JAVA_EXIT=%ERRORLEVEL%"

if "%JAVA_EXIT%"=="0" echo [SUCESSO] Lote SFTP finalizado.
if not "%JAVA_EXIT%"=="0" echo [ERRO] Lote SFTP finalizado com codigo %JAVA_EXIT%.
exit /b %JAVA_EXIT%
