@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0"

:menu
cls
echo(
echo( SATELITE TMS
echo(
echo( =================================================
echo(           [ SERVIÇOS EM BACKGROUND ^(API^) ]
echo( -------------------------------------------------
echo( 1. Iniciar API + Loop Vedacit
echo( 2. Iniciar API + Loop PPG
echo( 3. Iniciar API + Loop ^(Ambos^)
echo( 4. Iniciar APENAS API ^(Sem Robô^)
echo(
echo( =================================================
echo(           [ EXECUÇÕES MANUAIS ^(FOREGROUND^) ]
echo( -------------------------------------------------
echo( 5. Forçar Ciclo Único [Vedacit]
echo( 6. Forçar Ciclo Único [PPG]
echo( 7. Executar Carga Retroativa
echo( 8. Executar Testes E2E Isolados
echo(
echo( =================================================
echo(           [ MONITORAMENTO E CONTROLE ]
echo( -------------------------------------------------
echo( 9. Acompanhar Logs ao Vivo
echo( A. Status do Sistema ^(Procurar portas ativas^)
echo( B. PARAR TUDO ^(Somente Java do Satelite^)
echo( 0. Sair
echo(
choice /c 123456789AB0 /n /m "Escolha uma opcao: "

if errorlevel 12 goto sair
if errorlevel 11 goto parar_robo
if errorlevel 10 goto status_robo
if errorlevel 9 goto logs
if errorlevel 8 goto testes_e2e
if errorlevel 7 goto carga_retroativa
if errorlevel 6 goto ciclo_unico_ppg
if errorlevel 5 goto ciclo_unico_vedacit
if errorlevel 4 goto iniciar_api_sem_robo
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
