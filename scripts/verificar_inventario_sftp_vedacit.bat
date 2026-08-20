@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
call "%~dp0_common.bat" require-jar validate-db
if errorlevel 1 exit /b 1
"%JAVA_EXECUTABLE%" -jar "%JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=false" "--APP_CICLO_UNICO=false" "--APP_ETL_REPESCAGEM_ENABLED=false" "--APP_PPG_ENABLED=false" "--APP_VEDACIT_ENABLED=false" "--APP_SELIA_ENABLED=false" "--APP_SUPPORTE_ENABLED=false" "--server.port=0" "--spring.main.web-application-type=none" "--SFTP_RODOGARCIA_ENABLED=true" "--VEDACIT_SFTP_RECEIPT_ONLY=true" "--vedacit.sftp-inventory.enabled=true"
exit /b %ERRORLEVEL%
