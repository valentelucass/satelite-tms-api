@echo off
setlocal

set "PROJECT_ROOT=%~dp0.."
set "ENV_FILE=%PROJECT_ROOT%\.env"

if exist "%ENV_FILE%" (
    echo Carregando configuracoes de %ENV_FILE%
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
        if not "%%A"=="" if not defined %%A set "%%A=%%B"
    )
)

set "DB_SERVER=%DB_SERVER%"
if "%DB_SERVER%"=="" (
    if not "%DB_HOST%"=="" (
        if not "%DB_PORT%"=="" (
            set "DB_SERVER=%DB_HOST%,%DB_PORT%"
        ) else (
            set "DB_SERVER=%DB_HOST%"
        )
    ) else (
        set "DB_SERVER=localhost"
    )
)

set "DB_NAME=%DB_NAME%"
if "%DB_NAME%"=="" set "DB_NAME=SATELITE_TMS_AUDITORIA"
set "ALLOWED_DB_NAME=SATELITE_TMS_AUDITORIA"
if /I not "%DB_NAME%"=="%ALLOWED_DB_NAME%" (
    echo [ERRO] Database nao permitida: %DB_NAME%
    echo Este repositorio so pode operar na database %ALLOWED_DB_NAME%.
    exit /b 1
)

if "%DB_TRUST_SERVER_CERTIFICATE%"=="" set "DB_TRUST_SERVER_CERTIFICATE=true"
set "SQLCMD_TLS_ARGS="
if /I not "%DB_TRUST_SERVER_CERTIFICATE%"=="false" set "SQLCMD_TLS_ARGS=-C"

set "DB_APP_USER=%DB_APP_USER%"
if "%DB_APP_USER%"=="" set "DB_APP_USER=%DB_USER%"

set "SCRIPT_ROOT=%~dp0sql"

where sqlcmd >nul 2>nul
if errorlevel 1 (
    echo [ERRO] sqlcmd nao encontrado no PATH.
    echo Instale o SQL Server Command Line Utilities ou o Microsoft sqlcmd.
    exit /b 1
)

echo.
echo ================================================
echo  Satelite TMS - Setup da database de auditoria
echo ================================================
echo Servidor: %DB_SERVER%
echo Database: %DB_NAME%
if "%DB_ADMIN_USER%"=="" (
    echo Setup auth: Windows Integrated ^(-E^)
) else (
    echo Setup auth: SQL Login ^(-U %DB_ADMIN_USER%^)
)
if "%DB_APP_USER%"=="" (
    echo App user: nao configurado
) else (
    echo App user: %DB_APP_USER%
)
if "%SQLCMD_TLS_ARGS%"=="" (
    echo TrustServerCertificate: false
) else (
    echo TrustServerCertificate: true ^(-C^)
)
echo.

call :run_sql "%SCRIPT_ROOT%\schema\00_create_database.sql"
if errorlevel 1 exit /b 1

call :run_sql "%SCRIPT_ROOT%\schema\01_create_tb_log_integracao.sql"
if errorlevel 1 exit /b 1

call :run_sql "%SCRIPT_ROOT%\schema\02_create_indexes.sql"
if errorlevel 1 exit /b 1

call :run_migrations
if errorlevel 1 exit /b 1

call :run_sql "%SCRIPT_ROOT%\schema\03_grant_app_user.sql"
if errorlevel 1 exit /b 1

echo.
echo [OK] Database %DB_NAME% pronta para o Satelite TMS.
echo.
exit /b 0

:run_migrations
set "MIGRATION_ROOT=%SCRIPT_ROOT%\migration"

if not exist "%MIGRATION_ROOT%\" (
    echo Nenhuma pasta de migrations encontrada em %MIGRATION_ROOT%.
    exit /b 0
)

set "FOUND_MIGRATION="
for /f "delims=" %%F in ('dir /b /a-d /on "%MIGRATION_ROOT%\*.sql" 2^>nul') do (
    set "FOUND_MIGRATION=1"
    call :run_sql "%MIGRATION_ROOT%\%%F"
    if errorlevel 1 exit /b 1
)

if "%FOUND_MIGRATION%"=="" echo Nenhuma migration SQL encontrada em %MIGRATION_ROOT%.
exit /b 0

:run_sql
set "SCRIPT_FILE=%~1"
echo Executando: %SCRIPT_FILE%

if "%DB_USER%"=="" (
    if "%DB_ADMIN_USER%"=="" (
        sqlcmd -S "%DB_SERVER%" %SQLCMD_TLS_ARGS% -I -E -b -i "%SCRIPT_FILE%" -v DatabaseName="%DB_NAME%" AppUser="%DB_APP_USER%"
    ) else (
        sqlcmd -S "%DB_SERVER%" %SQLCMD_TLS_ARGS% -I -U "%DB_ADMIN_USER%" -P "%DB_ADMIN_PASSWORD%" -b -i "%SCRIPT_FILE%" -v DatabaseName="%DB_NAME%" AppUser="%DB_APP_USER%"
    )
) else (
    if "%DB_ADMIN_USER%"=="" (
        sqlcmd -S "%DB_SERVER%" %SQLCMD_TLS_ARGS% -I -E -b -i "%SCRIPT_FILE%" -v DatabaseName="%DB_NAME%" AppUser="%DB_APP_USER%"
    ) else (
        sqlcmd -S "%DB_SERVER%" %SQLCMD_TLS_ARGS% -I -U "%DB_ADMIN_USER%" -P "%DB_ADMIN_PASSWORD%" -b -i "%SCRIPT_FILE%" -v DatabaseName="%DB_NAME%" AppUser="%DB_APP_USER%"
    )
)

if errorlevel 1 (
    echo [ERRO] Falha executando %SCRIPT_FILE%.
    exit /b 1
)

exit /b 0
