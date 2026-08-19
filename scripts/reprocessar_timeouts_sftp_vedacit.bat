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
echo  Retentativa Controlada - Timeouts Vedacit/SFTP
echo =========================================================
echo Seleciona somente Read timed out de canhotos SFTP reconciliados.
echo Nao inclui recusas da Vedacit. Usa espera de 5 minutos e prazo SOAP de 5 minutos.
echo Cada item pode ja ter sido recebido pela Vedacit; resposta de duplicidade sera sucesso.
echo.
set /p "CONFIRMACAO=Digite RETENTAR para iniciar um unico timeout: "
if /I not "%CONFIRMACAO%"=="RETENTAR" (
    echo Operacao cancelada.
    exit /b 0
)

"%JAVA_EXECUTABLE%" -jar "%EXECUTABLE_JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=false" "--APP_NIGHTLY_RETRY_ENABLED=false" "--APP_CICLO_UNICO=false" "--APP_ETL_REPESCAGEM_ENABLED=false" "--APP_PPG_ENABLED=false" "--APP_VEDACIT_ENABLED=false" "--APP_SELIA_ENABLED=false" "--APP_SUPPORTE_ENABLED=false" "--server.port=0" "--spring.main.web-application-type=none" "--SFTP_RODOGARCIA_ENABLED=true" "--VEDACIT_SFTP_RECEIPT_ONLY=true" "--VEDACIT_SFTP_RECONCILIATION_ENABLED=true" "--VEDACIT_SOAP_READ_TIMEOUT_MS=300000" "--VEDACIT_SOAP_INVOCATION_TIMEOUT_MS=330000" "--vedacit.sftp-timeout-retry.enabled=true" "--vedacit.sftp-timeout-retry.max-items=1" "--vedacit.sftp-timeout-retry.max-attempts=2" "--vedacit.sftp-timeout-retry.interval-ms=300000"
set "JAVA_EXIT=%ERRORLEVEL%"

if "%JAVA_EXIT%"=="0" echo [SUCESSO] Retentativa controlada finalizada.
if not "%JAVA_EXIT%"=="0" echo [ERRO] Retentativa controlada finalizada com codigo %JAVA_EXIT%.
exit /b %JAVA_EXIT%
