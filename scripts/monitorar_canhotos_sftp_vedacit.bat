@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

call "%~dp0_common.bat" require-jar validate-db
if errorlevel 1 exit /b 1

echo.
echo =========================================================
echo  Monitor SFTP de Canhotos Vedacit Pendentes
echo =========================================================
echo Processa no maximo 100 canhotos por rodada, priorizando NF-es
echo que possuem comprovante no SFTP e reconciliando o CT-e pelo XML.
echo Enquanto houver lotes completos com progresso, segue
echo automaticamente a cada 30 segundos. O intervalo informado so vale
echo quando nao houver mais candidatos elegiveis. Use Ctrl+C para encerrar.
echo O dreno pausa somente se uma rodada atingir o limite seguro de erros.
echo Pressione Enter sem digitar para aceitar cada valor padrao.
echo.
set "BATCH_LIMIT=%~1"
if not defined BATCH_LIMIT set "BATCH_LIMIT=100"

powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $n = [int]'%BATCH_LIMIT%'; if ($n -lt 1 -or $n -gt 100) { exit 2 }; exit 0 } catch { exit 1 }"
if errorlevel 2 (
    echo [ERRO] Informe uma quantidade entre 1 e 100.
    exit /b 1
)
if errorlevel 1 (
    echo [ERRO] Informe uma quantidade inteira.
    exit /b 1
)

set "WAIT_MINUTES=%~2"
if not defined WAIT_MINUTES set "WAIT_MINUTES=30"

powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $n = [int]'%WAIT_MINUTES%'; if ($n -lt 1 -or $n -gt 720) { exit 2 }; exit 0 } catch { exit 1 }"
if errorlevel 2 (
    echo [ERRO] Informe um intervalo entre 1 e 720 minutos.
    exit /b 1
)
if errorlevel 1 (
    echo [ERRO] Informe um intervalo inteiro em minutos.
    exit /b 1
)

set /a "WAIT_SECONDS=WAIT_MINUTES*60"
set "VEDACIT_SFTP_DRAIN_UNTIL_IDLE=true"
set "VEDACIT_SFTP_RECONCILIATION_ENABLED=true"
set "VEDACIT_SFTP_DRAIN_MAX_ERRORS_PER_ROUND=25"
echo.
echo Regra operacional: lotes de %BATCH_LIMIT%, pausa curta de 30 segundos entre lotes ativos;
echo nova consulta a cada %WAIT_MINUTES% minuto(s) somente apos esgotar os candidatos.
echo Ate 24 erros isolados por rodada ficam registrados e o dreno segue para os proximos itens.
echo Ao esgotar a fila normal com timeouts antigos, abre automaticamente a retentativa P.
echo.

:nova_rodada
echo [%date% %time%] Iniciando rodada SFTP Vedacit...
call "%~dp0reprocessar_canhotos_sftp_vedacit.bat" %BATCH_LIMIT%
set "BATCH_EXIT=%ERRORLEVEL%"
if "%BATCH_EXIT%"=="2" (
    echo [ERRO] Falha critica no lote. Monitor interrompido sem retentativa automatica.
    exit /b 2
)
if "%BATCH_EXIT%"=="3" (
    echo.
    echo [ATENCAO] Fila normal esgotada; ha timeout^(s^) ambiguo^(s^) pendente^(s^).
    echo A opcao P sera aberta agora para uma retentativa controlada.
    call "%~dp0reprocessar_timeouts_sftp_vedacit.bat"
    echo Monitor encerrado apos a retentativa controlada. Inicie K novamente para conferir a proxima fila.
    exit /b 0
)
if not "%BATCH_EXIT%"=="0" (
    echo.
    echo [ATENCAO] Lote concluido com erros individuais registrados.
    echo Monitor interrompido para preservar os erros para analise e reconciliacao.
    echo Timeouts ambiguos so sao tratados pela opcao P quando o lote retornar codigo 3.
    exit /b %BATCH_EXIT%
)

echo [%date% %time%] Rodada concluida. Proxima consulta em %WAIT_MINUTES% minuto(s). Ctrl+C para encerrar.
timeout /t %WAIT_SECONDS% /nobreak >nul
goto nova_rodada
