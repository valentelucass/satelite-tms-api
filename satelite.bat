@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0"

:menu
cls
echo.
echo  SATELITE TMS
echo.
echo  =============================================================
echo                    SATELITE TMS CONTROL CENTER
echo  =============================================================
echo.
echo  1. Iniciar Loop 15 min [APENAS VEDACIT] - Background
echo  2. Iniciar Loop 15 min [APENAS PPG] - Background
echo  3. Iniciar Loop 15 min [VEDACIT + PPG] - Background
echo  4. Executar Ciclo Único [Apenas Vedacit] - Foreground
echo  5. Executar Ciclo Único [Apenas PPG] - Foreground
echo  6. Acompanhar Logs ao Vivo
echo  7. Status do Robô (Porta 9090 / PID)
echo  8. Parar tudo [API / Robo / Java Satelite]
echo  9. Testes E2E (PPG / Vedacit)
echo  A. Carga Retroativa - Foreground
echo  B. Iniciar [API Servidor - Sem robo] - Background
echo  0. Sair
echo.
choice /c 123456789AB0 /n /m "Escolha uma opcao: "

if errorlevel 12 goto sair
if errorlevel 11 goto iniciar_api_sem_robo
if errorlevel 10 goto carga_retroativa
if errorlevel 9 goto testes_e2e
if errorlevel 8 goto parar_robo
if errorlevel 7 goto status_robo
if errorlevel 6 goto logs
if errorlevel 5 goto ciclo_unico_ppg
if errorlevel 4 goto ciclo_unico_vedacit
if errorlevel 3 goto iniciar_todos
if errorlevel 2 goto iniciar_ppg
if errorlevel 1 goto iniciar_vedacit

:iniciar_vedacit
call scripts\iniciar_background.bat "Loop 15 min Vedacit" "true" "false" "logs\satelite_vedacit.log"
if errorlevel 1 goto pausar_menu
goto menu

:iniciar_ppg
call scripts\iniciar_background.bat "Loop 15 min PPG" "false" "true" "logs\satelite_ppg.log"
if errorlevel 1 goto pausar_menu
goto menu

:iniciar_todos
call scripts\iniciar_background.bat "Loop 15 min Vedacit + PPG" "true" "true" "logs\satelite_vedacit_ppg.log"
if errorlevel 1 goto pausar_menu
goto menu

:iniciar_api_sem_robo
call scripts\iniciar_background.bat "API Servidor - Sem robo" "false" "false" "logs\satelite_api_sem_robo.log" "false"
if errorlevel 1 goto pausar_menu
goto menu

:ciclo_unico_vedacit
call scripts\iniciar_foreground.bat "Ciclo unico Vedacit" "true" "false"
goto pausar_menu

:ciclo_unico_ppg
call scripts\iniciar_foreground.bat "Ciclo unico PPG" "false" "true"
goto pausar_menu

:logs
call scripts\logs.bat
if errorlevel 1 goto pausar_menu
goto menu

:status_robo
call scripts\status.bat
goto pausar_menu

:parar_robo
call scripts\parar.bat
goto pausar_menu

:testes_e2e
call scripts\testes_e2e.bat
goto pausar_menu

:carga_retroativa
call scripts\retroativo.bat
goto pausar_menu

:pausar_menu
echo.
pause
goto menu

:sair
cls
echo.
echo Encerrando.
exit /b 0
