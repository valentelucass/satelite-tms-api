@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."
call "%~dp0_common.bat" require-jar validate-db
if errorlevel 1 exit /b 1

set "LIMIT=%~1"
if not defined LIMIT set /p "LIMIT=Quantidade para a previa [100]: "
if not defined LIMIT set "LIMIT=100"

powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $n=[int]'%LIMIT%'; if($n -lt 1 -or $n -gt 500){exit 2}; exit 0 } catch {exit 1}"
if errorlevel 2 (
    echo [ERRO] Informe uma quantidade entre 1 e 500.
    exit /b 1
)
if errorlevel 1 (
    echo [ERRO] Informe uma quantidade inteira.
    exit /b 1
)

echo.
echo Previa Vedacit: le SFTP e XML ESL para classificar. Nao envia SOAP e nao altera status.
"%JAVA_EXECUTABLE%" -jar "%JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=false" "--APP_ETL_REPESCAGEM_ENABLED=false" "--APP_ETL_PENDENCIAS_ENABLED=false" "--APP_PPG_ENABLED=false" "--APP_VEDACIT_ENABLED=false" "--APP_SELIA_ENABLED=false" "--APP_SUPPORTE_ENABLED=false" "--server.port=0" "--spring.main.web-application-type=none" "--SFTP_RODOGARCIA_ENABLED=true" "--vedacit.sftp-receipt-reconciliation-preview.enabled=true" "--vedacit.sftp-receipt-reconciliation-preview.max-items=%LIMIT%"
exit /b %ERRORLEVEL%
