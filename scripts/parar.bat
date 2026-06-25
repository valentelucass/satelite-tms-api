@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat"
if errorlevel 1 exit /b 1

echo Parando Robo Satelite...

set "ENCONTROU_PROCESSO=false"
set "FALHA_ENCERRAMENTO=false"

echo Encerrando somente processos java/javaw do artefato Satelite, se existirem...
call :encerrar_processos_satelite "java.exe"
call :encerrar_processos_satelite "javaw.exe"
if /I "%FALHA_ENCERRAMENTO%"=="true" exit /b 1
call :encerrar_processo_java_na_porta
if errorlevel 1 exit /b 1

call :aguardar_porta_liberada 15
if errorlevel 1 (
    echo [AVISO] A porta %PORTA_API% continua ocupada por processo que nao foi identificado como Satelite.
    exit /b 1
)

call :aguardar_jar_liberado 20
if errorlevel 1 exit /b 1

if /I "%ENCONTROU_PROCESSO%"=="true" (
    echo [SUCESSO] Processos do Satelite encerrados.
) else (
    echo Nenhum processo Java do Satelite foi encontrado.
)
exit /b 0

:encerrar_processo_java_na_porta
set "PORTA_API_PID="
for /f "tokens=5" %%P in ('netstat -aon ^| findstr /R /C:":%PORTA_API% .*LISTENING"') do (
    set "PORTA_API_PID=%%P"
)
if not defined PORTA_API_PID exit /b 0

set "PORTA_API_PROCESSO="
for /f "usebackq delims=" %%N in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "try { (Get-Process -Id %PORTA_API_PID% -ErrorAction Stop).ProcessName } catch { exit 1 }"`) do (
    set "PORTA_API_PROCESSO=%%N"
)

if /I "%PORTA_API_PROCESSO%"=="java" goto encerrar_pid_porta
if /I "%PORTA_API_PROCESSO%"=="javaw" goto encerrar_pid_porta

if not "%PORTA_API_PROCESSO%"=="" (
    echo [AVISO] Porta %PORTA_API% ocupada por PID %PORTA_API_PID% ^(%PORTA_API_PROCESSO%^) e nao sera encerrada automaticamente.
) else (
    echo [AVISO] Porta %PORTA_API% ocupada por PID %PORTA_API_PID%, mas o processo nao foi identificado.
)
exit /b 0

:encerrar_pid_porta
set "ENCONTROU_PROCESSO=true"
echo Encerrando %PORTA_API_PROCESSO%.exe do Satelite na porta %PORTA_API%: PID %PORTA_API_PID%
taskkill /F /T /PID %PORTA_API_PID% >nul 2>&1
if errorlevel 1 (
    echo [ERRO] Nao foi possivel encerrar PID %PORTA_API_PID%. Feche o processo manualmente ou execute o terminal como administrador.
    exit /b 1
)
exit /b 0

:aguardar_jar_liberado
set "TENTATIVAS=%~1"
if "%TENTATIVAS%"=="" set "TENTATIVAS=20"
if not exist "%JAR_PATH%" exit /b 0

:aguardar_jar_liberado_loop
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $p = '%JAR_PATH%'; $fs = [IO.File]::Open($p, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None); $fs.Close(); exit 0 } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 exit /b 0
if "%TENTATIVAS%"=="0" (
    echo [ERRO] JAR ainda bloqueado: %JAR_PATH%
    echo Feche execucoes manuais, cargas retroativas ou processos Java que estejam usando este artefato.
    call :listar_processos_satelite_restantes "java.exe"
    call :listar_processos_satelite_restantes "javaw.exe"
    exit /b 1
)
set /a TENTATIVAS-=1
ping -n 2 127.0.0.1 >nul
goto aguardar_jar_liberado_loop

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
ping -n 2 127.0.0.1 >nul
goto aguardar_porta_liberada_loop

:encerrar_processos_satelite
set "PROCESSO_JAVA=%~1"
for /f "tokens=2 delims==" %%P in ('wmic path Win32_Process where "Name='%PROCESSO_JAVA%' and CommandLine like '%%satelite-tms-api%%target%%satelite-0.0.1-SNAPSHOT.jar%%'" get ProcessId /value 2^>nul ^| findstr /R /C:"^ProcessId=[0-9][0-9]*"') do (
    call :encerrar_pid "%PROCESSO_JAVA%" "%%P"
)
exit /b 0

:encerrar_pid
set "PROCESSO_JAVA=%~1"
set "PID_ALVO=%~2"
echo %PID_ALVO%| findstr /R /C:"^[0-9][0-9]*$" >nul
if errorlevel 1 exit /b 0
set "ENCONTROU_PROCESSO=true"
echo Encerrando %PROCESSO_JAVA% do Satelite: PID %PID_ALVO%
taskkill /F /T /PID %PID_ALVO% >nul 2>&1
if errorlevel 1 (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Get-Process -Id %PID_ALVO% -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }" >nul 2>&1
    if not errorlevel 1 (
        echo [ERRO] Falha ao encerrar %PROCESSO_JAVA% PID %PID_ALVO%. Feche manualmente ou execute o terminal como administrador.
        set "FALHA_ENCERRAMENTO=true"
    )
)
exit /b 0

:listar_processos_satelite_restantes
set "PROCESSO_JAVA=%~1"
for /f "tokens=2 delims==" %%P in ('wmic path Win32_Process where "Name='%PROCESSO_JAVA%' and CommandLine like '%%satelite-tms-api%%target%%satelite-0.0.1-SNAPSHOT.jar%%'" get ProcessId /value 2^>nul ^| findstr /R /C:"^ProcessId=[0-9][0-9]*"') do (
    echo [BLOQUEIO] %PROCESSO_JAVA% ainda ativo usando o JAR: PID %%P
)
exit /b 0
