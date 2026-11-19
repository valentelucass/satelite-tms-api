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
echo(           [ SERVICOS DO PM2 - SATELITE-TMS ]
echo( -------------------------------------------------
echo( 1. Iniciar API do dashboard e recebimento SELIA
echo( 2. Iniciar processamento de clientes e reconciliacao
echo( 3. Iniciar os dois programas
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
echo( J. Processar lote SFTP de Canhotos Vedacit Pendentes
echo( K. Monitorar lote SFTP de Canhotos Vedacit a cada 30 min
echo( N. Reprocessar Pendencias Tecnicas Vedacit
echo( P. Retentar um Timeout SFTP Vedacit com espera ampliada
echo( T. Executar Testes E2E Isolados
echo(
echo( =================================================
echo(           [ MONITORAMENTO E CONTROLE ]
echo( -------------------------------------------------
echo( L. Acompanhar Logs ao Vivo
echo( A. Status dos dois programas no PM2
echo( B. Parar os dois programas do Satelite
echo( 0. Sair
echo(
choice /c 123789ERHJKNPTLAB0 /n /m "Escolha uma opcao: "
if errorlevel 19 goto pausar_menu
if errorlevel 18 goto sair
if errorlevel 17 goto parar_robo
if errorlevel 16 goto status_robo
if errorlevel 15 goto logs
if errorlevel 14 goto testes_e2e
if errorlevel 13 goto reprocessar_timeouts_sftp_vedacit
if errorlevel 12 goto reprocessar_pendencias_tecnicas_vedacit
if errorlevel 11 goto monitorar_canhotos_sftp_vedacit
if errorlevel 10 goto reprocessar_canhotos_sftp_vedacit
if errorlevel 9 goto reprocessar_canhotos_vedacit
if errorlevel 8 goto carga_retroativa
if errorlevel 7 goto ciclo_unico_supporte
if errorlevel 6 goto ciclo_unico_selia
if errorlevel 5 goto ciclo_unico_ppg
if errorlevel 4 goto ciclo_unico_vedacit
if errorlevel 3 goto iniciar_todos
if errorlevel 2 goto iniciar_clientes
if errorlevel 1 goto iniciar_api

:iniciar_api
call scripts\iniciar_api_dashboard.bat
goto pausar_menu

:iniciar_clientes
call scripts\iniciar_work_sftp_clientes.bat
goto pausar_menu

:iniciar_todos
call scripts\iniciar_background.bat
goto pausar_menu

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

:reprocessar_canhotos_sftp_vedacit
call scripts\reprocessar_canhotos_sftp_vedacit.bat
goto pausar_menu

:monitorar_canhotos_sftp_vedacit
call scripts\monitorar_canhotos_sftp_vedacit.bat
goto pausar_menu

:reprocessar_pendencias_tecnicas_vedacit
call scripts\reprocessar_pendencias_tecnicas_vedacit.bat
goto pausar_menu

:reprocessar_timeouts_sftp_vedacit
call scripts\reprocessar_timeouts_sftp_vedacit.bat
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
