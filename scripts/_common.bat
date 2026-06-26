@echo off

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_ROOT=%%~fI"

set "ALLOWED_DB_NAME=SATELITE_TMS_AUDITORIA"
set "DEFAULT_DB_URL=jdbc:sqlserver://localhost:1433;databaseName=SATELITE_TMS_AUDITORIA;encrypt=true;trustServerCertificate=true"
set "JAR_PATH=%PROJECT_ROOT%\target\satelite-0.0.1-SNAPSHOT.jar"
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
    if not exist "%JAR_PATH%" (
        echo.
        echo [ERRO] JAR nao encontrado: %JAR_PATH%
        echo Execute mvn package antes de iniciar o robo.
        exit /b 1
    )
)

if not exist "%LOGS_DIR%" mkdir "%LOGS_DIR%" >nul 2>&1

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
