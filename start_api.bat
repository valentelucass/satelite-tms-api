@echo off
setlocal EnableExtensions
title Satelite TMS - API Server e Scheduler
color 0A

chcp 65001 >nul

:: Navega para a pasta raiz do projeto.
cd /d "%~dp0"

:: Porta dedicada do modo servidor continuo.
:: Mantem este runtime isolado do menu satelite.bat e de outras apps Java.
if "%SATELITE_API_PORT%"=="" set "SATELITE_API_PORT=19090"

:LOOP
call "%~dp0scripts\_common.bat" require-jar validate-db
if errorlevel 1 goto retry_start

set "SERVER_PORT=%SATELITE_API_PORT%"
set "PORTA_API=%SATELITE_API_PORT%"

if "%APP_SCHEDULER_ENABLED%"=="" set "APP_SCHEDULER_ENABLED=true"
if "%APP_PPG_ENABLED%"=="" set "APP_PPG_ENABLED=true"
if "%APP_VEDACIT_ENABLED%"=="" set "APP_VEDACIT_ENABLED=true"
if "%INTEGRATION_SCHEDULER_INTERVAL_MS%"=="" set "INTEGRATION_SCHEDULER_INTERVAL_MS=%BACKGROUND_INTERVAL_MS%"

cls
echo ========================================================
echo A INICIAR SATELITE TMS - MODO SERVIDOR CONTINUO
echo ========================================================
echo.
echo API REST ativa na porta %SATELITE_API_PORT%.
echo Scheduler configurado: APP_SCHEDULER_ENABLED=%APP_SCHEDULER_ENABLED%
echo Intervalo configurado: %INTEGRATION_SCHEDULER_INTERVAL_MS% ms
echo.
echo Endpoint local: http://localhost:%SATELITE_API_PORT%
echo Pressiona CTRL+C para parar o servidor definitivamente.
echo ========================================================
echo.

netstat -ano | findstr /R /C:":%SATELITE_API_PORT% .*LISTENING" >nul
if not errorlevel 1 (
    echo [ATENCAO] A porta %SATELITE_API_PORT% ja esta em uso.
    echo Verifique se outro processo do Satelite/API ja esta ativo.
    goto retry_start
)

:: Inicia a aplicacao Spring Boot compilada em modo API + scheduler.
java -jar "%JAR_PATH%" "--debug=false" "--APP_SCHEDULER_ENABLED=%APP_SCHEDULER_ENABLED%" "--APP_CICLO_UNICO=false" "--APP_PPG_ENABLED=%APP_PPG_ENABLED%" "--APP_VEDACIT_ENABLED=%APP_VEDACIT_ENABLED%" "--server.port=%SATELITE_API_PORT%" "--spring.main.web-application-type=servlet" "--INTEGRATION_SCHEDULER_INTERVAL_MS=%INTEGRATION_SCHEDULER_INTERVAL_MS%"
set "JAVA_EXIT=%ERRORLEVEL%"

echo.
echo [ATENCAO] O servidor foi interrompido, crashou ou foi atualizado.
echo Codigo de saida do Java: %JAVA_EXIT%
echo A reiniciar o motor automaticamente em 10 segundos...
timeout /t 10 /nobreak >nul
goto LOOP

:retry_start
echo.
echo A tentar iniciar novamente em 10 segundos...
timeout /t 10 /nobreak >nul
goto LOOP
