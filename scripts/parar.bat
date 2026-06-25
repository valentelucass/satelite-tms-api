@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat"
if errorlevel 1 exit /b 1

echo Parando Robo Satelite...

set "ENCONTROU_PROCESSO=false"

echo Encerrando somente processos java/javaw do artefato Satelite, se existirem...
call :encerrar_processos_satelite "java.exe"
call :encerrar_processos_satelite "javaw.exe"

call :aguardar_porta_liberada 15
if errorlevel 1 (
    echo [AVISO] A porta %PORTA_API% continua ocupada por processo que nao foi identificado como Satelite.
    exit /b 1
)

if /I "%ENCONTROU_PROCESSO%"=="true" (
    echo [SUCESSO] Processos do Satelite encerrados.
) else (
    echo Nenhum processo Java do Satelite foi encontrado.
)
exit /b 0

:aguardar_porta_liberada
set "TENTATIVAS=%~1"
if "%TENTATIVAS%"=="" set "TENTATIVAS=15"

:aguardar_porta_liberada_loop
set "PORTA_API_PID="
for /f "tokens=5" %%P in ('netstat -aon ^| findstr /R /C:":%PORTA_API% .*LISTENING"') do (
    set "PORTA_API_PID=%%P"
)
if not defined PORTA_API_PID exit /b 0
if "%TENTATIVAS%"=="0" exit /b 1
set /a TENTATIVAS-=1
timeout /t 1 /nobreak >nul
goto aguardar_porta_liberada_loop

:encerrar_processos_satelite
set "PROCESSO_JAVA=%~1"
for /f "skip=1 tokens=2 delims=," %%P in ('wmic process where "name='%PROCESSO_JAVA%' and commandline like '%%satelite-tms-api%%target%%satelite-0.0.1-SNAPSHOT.jar%%'" get ProcessId /format:csv 2^>nul') do (
    if not "%%P"=="" (
        set "ENCONTROU_PROCESSO=true"
        echo Encerrando %PROCESSO_JAVA% do Satelite: PID %%P
        taskkill /F /PID %%P >nul 2>&1
    )
)
exit /b 0
