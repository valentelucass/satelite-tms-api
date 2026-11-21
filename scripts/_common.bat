@echo off

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_ROOT=%%~fI"

set "ALLOWED_DB_NAME=SATELITE_TMS_AUDITORIA"
set "DEFAULT_DB_URL=jdbc:sqlserver://localhost:1433;databaseName=SATELITE_TMS_AUDITORIA;encrypt=true;trustServerCertificate=true"
set "JAR_PATH="
set "JAVA_17_EXECUTABLE=%PROJECT_ROOT%\.tools\temurin17\jdk-17.0.19+10\bin\java.exe"
set "PORTA_API=9090"
set "LOGS_DIR=%PROJECT_ROOT%\logs"
set "BACKGROUND_INTERVAL_MS=900000"

set "COMMON_REQUIRE_JAR=false"
set "COMMON_VALIDATE_DB=false"

:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="require-jar" set "COMMON_REQUIRE_JAR=true"
if /I "%~1"=="validate-db" set "COMMON_VALIDATE_DB=true"
shift
goto parse_args

:args_done
call :carregar_env

if not "%SERVER_PORT%"=="" set "PORTA_API=%SERVER_PORT%"
if "%SERVER_PORT%"=="" set "SERVER_PORT=%PORTA_API%"

if "%DB_NAME%"=="" set "DB_NAME=%ALLOWED_DB_NAME%"
if "%DB_URL%"=="" set "DB_URL=%DEFAULT_DB_URL%"
if "%SATELITE_DB_URL%"=="" set "SATELITE_DB_URL=%DB_URL%"
if "%SATELITE_DB_USER%"=="" set "SATELITE_DB_USER=%DB_USER%"
if "%SATELITE_DB_PASSWORD%"=="" set "SATELITE_DB_PASSWORD=%DB_PASSWORD%"

if not "%SATELITE_DB_URL%"=="" set "SPRING_DATASOURCE_URL=%SATELITE_DB_URL%"
if not "%SATELITE_DB_USER%"=="" set "SPRING_DATASOURCE_USERNAME=%SATELITE_DB_USER%"
if not "%SATELITE_DB_PASSWORD%"=="" set "SPRING_DATASOURCE_PASSWORD=%SATELITE_DB_PASSWORD%"

if /I "%COMMON_VALIDATE_DB%"=="true" call :validar_database_permitida
if errorlevel 1 exit /b 1

if /I "%COMMON_REQUIRE_JAR%"=="true" (
    call :selecionar_pacote_clientes
    if errorlevel 1 exit /b 1
)

if exist "%JAVA_17_EXECUTABLE%" set "JAVA_EXECUTABLE=%JAVA_17_EXECUTABLE%"
if not defined JAVA_EXECUTABLE set "JAVA_EXECUTABLE=java"

if not exist "%LOGS_DIR%" mkdir "%LOGS_DIR%" >nul 2>&1

exit /b 0

:selecionar_pacote_clientes
set "JAR_PATH="
for /f "delims=" %%J in ('node -e "const p=require(process.argv[1]).apps.find(x=>x.name==='SATELITE-TMS-CLIENTES' && x.namespace==='satelite-tms'); console.log(p.args[p.args.indexOf('-jar')+1])" "%PROJECT_ROOT%\deploy\clientes\ecosystem.config.js"') do set "JAR_PATH=%%J"
if not defined JAR_PATH (
    echo [ERRO] Pacote de clientes indisponivel. Confira deploy\clientes\pacotes.
    exit /b 1
)
if not exist "%JAR_PATH%" exit /b 1
rem Os comandos manuais solicitam uma tarefa explicita, sem iniciar as agendas residentes.
set "APP_CLIENTES_WORKER_ENABLED=false"
set "APP_RECONCILIACAO_ENABLED=false"
exit /b 0

:carregar_env
set "ENV_FILE=%PROJECT_ROOT%\.env"
if exist "%ENV_FILE%" (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
        if not "%%A"=="" set "%%A=%%B"
    )
)
exit /b 0

:validar_database_permitida
if /I not "%DB_NAME%"=="%ALLOWED_DB_NAME%" (
    echo.
    echo [ERRO] Database nao permitida: %DB_NAME%
    echo Este repositorio so pode operar na database %ALLOWED_DB_NAME%.
    exit /b 1
)

if not "%SATELITE_DB_URL%"=="" (
    echo "%SATELITE_DB_URL%" | findstr /I /C:"databaseName=%ALLOWED_DB_NAME%" >nul
    if errorlevel 1 (
        echo.
        echo [ERRO] SATELITE_DB_URL nao aponta para %ALLOWED_DB_NAME%.
        echo Ajuste o .env antes de rodar o Satelite TMS.
        exit /b 1
    )
)
exit /b 0
