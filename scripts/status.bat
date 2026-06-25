@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat"
if errorlevel 1 exit /b 1

set "PORTA_API_PID="
for /f "tokens=5" %%P in ('netstat -aon ^| findstr /R /C:":%PORTA_API% .*LISTENING"') do (
    set "PORTA_API_PID=%%P"
)

echo Status do Robo Satelite
echo.
if defined PORTA_API_PID (
    echo Porta %PORTA_API% em LISTENING no PID %PORTA_API_PID%.
    echo.
    wmic process where "ProcessId=%PORTA_API_PID%" get ProcessId,Name,CommandLine /format:list 2>nul
) else (
    echo Nenhum processo ouvindo na porta %PORTA_API%.
)

exit /b 0
