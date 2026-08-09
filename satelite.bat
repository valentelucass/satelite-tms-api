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
echo( 3. Iniciar API + Loop ^(Vedacit + PPG^)
echo( 4. Iniciar API + Loop SELIA
echo( 5. Iniciar API + Loop SUPPORTE
echo( 6. Iniciar APENAS API ^(Sem Robô^)
echo(
echo( =================================================
echo(           [ EXECUÇÕES MANUAIS ^(FOREGROUND^) ]
echo( -------------------------------------------------
echo( 7. Forçar Ciclo Único [Vedacit]
echo( 8. Forçar Ciclo Único [PPG]
echo( 9. Forçar Ciclo Único [SELIA/AddEvents]
echo( E. Forçar Ciclo Único [SUPPORTE]
echo( R. Executar Carga Retroativa
echo( H. Reprocessar somente Canhotos Vedacit com Erro
echo( N. Reprocessar Pendencias Tecnicas Vedacit
echo( T. Executar Testes E2E Isolados
echo(
echo( =================================================
echo(           [ MONITORAMENTO E CONTROLE ]
echo( -------------------------------------------------
echo( L. Acompanhar Logs ao Vivo
echo( A. Status do Sistema ^(Procurar portas ativas^)
echo( B. PARAR TUDO ^(Somente Java do Satelite^)
echo( 0. Sair
echo(
choice /c 123456789ERHNTLAB0 /n /m "Escolha uma opcao: "

if errorlevel 18 goto sair
if errorlevel 17 goto parar_robo
if errorlevel 16 goto status_robo
if errorlevel 15 goto logs
if errorlevel 14 goto testes_e2e
if errorlevel 13 goto reprocessar_pendencias_tecnicas_vedacit
if errorlevel 12 goto reprocessar_canhotos_vedacit
if errorlevel 11 goto carga_retroativa
if errorlevel 10 goto ciclo_unico_supporte
if errorlevel 9 goto ciclo_unico_selia
if errorlevel 8 goto ciclo_unico_ppg
if errorlevel 7 goto ciclo_unico_vedacit
if errorlevel 6 goto iniciar_api_sem_robo
if errorlevel 5 goto iniciar_supporte
if errorlevel 4 goto iniciar_selia
if errorlevel 3 goto iniciar_todos
if errorlevel 2 goto iniciar_ppg
if errorlevel 1 goto iniciar_vedacit

:iniciar_vedacit
call scripts\iniciar_background.bat "Loop 15 min Vedacit" "true" "false" "false" "false" "logs\satelite_vedacit.log"
if errorlevel 1 goto pausar_menu
goto menu

:iniciar_ppg
call scripts\iniciar_background.bat "Loop 15 min PPG" "false" "true" "false" "false" "logs\satelite_ppg.log"
if errorlevel 1 goto pausar_menu
goto menu

:iniciar_todos
call scripts\iniciar_background.bat "Loop 15 min Vedacit + PPG" "true" "true" "false" "false" "logs\satelite_vedacit_ppg.log"
if errorlevel 1 goto pausar_menu
goto menu

:iniciar_selia
call scripts\iniciar_background.bat "Loop 15 min SELIA" "false" "false" "true" "false" "logs\satelite_selia.log"
if errorlevel 1 goto pausar_menu
goto menu

:iniciar_supporte
call scripts\iniciar_background.bat "Loop 15 min SUPPORTE" "false" "false" "false" "true" "logs\satelite_supporte.log"
if errorlevel 1 goto pausar_menu
goto menu

:iniciar_api_sem_robo
call scripts\iniciar_background.bat "API Servidor - Sem robo" "false" "false" "false" "false" "logs\satelite_api_sem_robo.log" "false"
if errorlevel 1 goto pausar_menu
goto menu

:ciclo_unico_vedacit
call scripts\iniciar_foreground.bat "Ciclo unico Vedacit" "true" "false" "false" "false"
goto pausar_menu

:ciclo_unico_ppg
call scripts\iniciar_foreground.bat "Ciclo unico PPG" "false" "true" "false" "false"
goto pausar_menu

:ciclo_unico_selia
call scripts\iniciar_foreground.bat "Ciclo unico SELIA/AddEvents" "false" "false" "true" "false"
goto pausar_menu

:ciclo_unico_supporte
call scripts\iniciar_foreground.bat "Ciclo unico SUPPORTE" "false" "false" "false" "true"
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

:reprocessar_canhotos_vedacit
call scripts\reprocessar_canhotos_vedacit.bat
goto pausar_menu

:reprocessar_pendencias_tecnicas_vedacit
call scripts\reprocessar_pendencias_tecnicas_vedacit.bat
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
