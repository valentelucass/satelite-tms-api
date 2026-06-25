@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat" require-jar validate-db
if errorlevel 1 exit /b 1

set "SATELITE_MODO=%~1"
set "APP_VEDACIT_ENABLED=%~2"
set "APP_PPG_ENABLED=%~3"

if "%SATELITE_MODO%"=="" set "SATELITE_MODO=Ciclo unico"
if "%APP_VEDACIT_ENABLED%"=="" set "APP_VEDACIT_ENABLED=true"
if "%APP_PPG_ENABLED%"=="" set "APP_PPG_ENABLED=true"

echo Iniciando modo [%SATELITE_MODO%] em foreground...
echo Modo trabalhador: sem Tomcat, sem disputa pela porta %PORTA_API%.
echo.

java -jar "%JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=false" "--APP_CICLO_UNICO=true" "--APP_PPG_ENABLED=%APP_PPG_ENABLED%" "--APP_VEDACIT_ENABLED=%APP_VEDACIT_ENABLED%" "--server.port=0" "--spring.main.web-application-type=none"
set "JAVA_EXIT=%ERRORLEVEL%"

if "%JAVA_EXIT%"=="0" echo [SUCESSO] Processo finalizado.
if not "%JAVA_EXIT%"=="0" echo [ERRO] Processo finalizado com codigo %JAVA_EXIT%.
exit /b %JAVA_EXIT%
