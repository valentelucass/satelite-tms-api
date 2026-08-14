@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat" require-jar validate-db
if errorlevel 1 exit /b 1

set "SATELITE_MODO=%~1"
set "APP_VEDACIT_ENABLED=%~2"
set "APP_PPG_ENABLED=%~3"
set "APP_SELIA_ENABLED=%~4"
set "APP_SUPPORTE_ENABLED=%~5"

if "%SATELITE_MODO%"=="" set "SATELITE_MODO=Ciclo unico"
if "%APP_VEDACIT_ENABLED%"=="" set "APP_VEDACIT_ENABLED=true"
if "%APP_PPG_ENABLED%"=="" set "APP_PPG_ENABLED=true"
if "%APP_SELIA_ENABLED%"=="" set "APP_SELIA_ENABLED=false"
if "%APP_SUPPORTE_ENABLED%"=="" set "APP_SUPPORTE_ENABLED=false"

echo Iniciando modo [%SATELITE_MODO%] em foreground...
echo Modo trabalhador: sem Tomcat, sem disputa pela porta %PORTA_API%.
echo.

if /I "%SATELITE_RUNTIME_CLASSPATH_AVAILABLE%"=="true" (
    "%JAVA_EXECUTABLE%" -cp "%SATELITE_CLASS_PATH%" com.example.satelite.SateliteApplication "--debug=false" "--APP_SCHEDULER_ENABLED=false" "--APP_CICLO_UNICO=true" "--APP_ETL_REPESCAGEM_ENABLED=false" "--APP_ETL_PENDENCIAS_ENABLED=false" "--APP_PPG_ENABLED=%APP_PPG_ENABLED%" "--APP_VEDACIT_ENABLED=%APP_VEDACIT_ENABLED%" "--APP_SELIA_ENABLED=%APP_SELIA_ENABLED%" "--APP_SUPPORTE_ENABLED=%APP_SUPPORTE_ENABLED%" "--SELIA_NFE_WHITELIST_ENABLED=false" "--VEDACIT_NFE_WHITELIST_ENABLED=true" "--server.port=0" "--spring.main.web-application-type=none"
) else (
    "%JAVA_EXECUTABLE%" -jar "%JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=false" "--APP_CICLO_UNICO=true" "--APP_ETL_REPESCAGEM_ENABLED=false" "--APP_ETL_PENDENCIAS_ENABLED=false" "--APP_PPG_ENABLED=%APP_PPG_ENABLED%" "--APP_VEDACIT_ENABLED=%APP_VEDACIT_ENABLED%" "--APP_SELIA_ENABLED=%APP_SELIA_ENABLED%" "--APP_SUPPORTE_ENABLED=%APP_SUPPORTE_ENABLED%" "--SELIA_NFE_WHITELIST_ENABLED=false" "--VEDACIT_NFE_WHITELIST_ENABLED=true" "--ETL_INCREMENTAL_LOOKBACK_HOURS=0" "--server.port=0" "--spring.main.web-application-type=none"
)
set "JAVA_EXIT=%ERRORLEVEL%"

if "%JAVA_EXIT%"=="0" echo [SUCESSO] Processo finalizado.
if not "%JAVA_EXIT%"=="0" echo [ERRO] Processo finalizado com codigo %JAVA_EXIT%.
exit /b %JAVA_EXIT%
