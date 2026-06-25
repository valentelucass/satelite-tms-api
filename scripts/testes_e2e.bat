@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat"
if errorlevel 1 exit /b 1

:menu_testes
cls
echo.
echo =========================================================
echo  Testes E2E
echo =========================================================
echo.
echo  1. Teste E2E PPG - Login + Ocorrencia
echo  2. Teste de Producao Vedacit - Ciclo Unico Isolado
echo  3. Voltar
echo.
choice /c 123 /n /m "Escolha uma opcao: "

if errorlevel 3 exit /b 0
if errorlevel 2 goto teste_vedacit
if errorlevel 1 goto teste_ppg

:teste_ppg
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0test_ppg_e2e.ps1"
exit /b %ERRORLEVEL%

:teste_vedacit
call "%~dp0iniciar_foreground.bat" "Teste de Producao Vedacit - Ciclo Unico Isolado" "true" "false"
exit /b %ERRORLEVEL%
