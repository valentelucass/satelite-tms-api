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
echo Processa no maximo 100 canhotos por rodada, somente pelo
echo SFTP. Enquanto houver lotes completos com confirmacoes, segue
echo automaticamente a cada 30 segundos. O intervalo informado so vale
echo quando nao houver mais candidatos elegiveis. Use Ctrl+C para encerrar.
echo Uma falha tecnica encerra o monitor para evitar repeticao cega.
echo Pressione Enter sem digitar para aceitar cada valor padrao.
echo.
set "BATCH_LIMIT=%~1"
if not defined BATCH_LIMIT set /p "BATCH_LIMIT=Quantidade maxima por rodada [100]: "
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
if not defined WAIT_MINUTES set /p "WAIT_MINUTES=Intervalo de nova consulta em minutos [30]: "
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
echo.
echo Monitor iniciado: lotes de %BATCH_LIMIT%, pausa curta de 30 segundos entre lotes ativos;
echo nova consulta a cada %WAIT_MINUTES% minuto(s) somente apos esgotar os candidatos.
echo.

:nova_rodada
echo [%date% %time%] Iniciando rodada SFTP Vedacit...
call "%~dp0reprocessar_canhotos_sftp_vedacit.bat" %BATCH_LIMIT%
if errorlevel 1 (
    echo [ERRO] Rodada encerrada com falha. Monitor interrompido; consulte o log antes de reiniciar.
    exit /b 1
)

echo [%date% %time%] Rodada concluida. Proxima consulta em %WAIT_MINUTES% minuto(s). Ctrl+C para encerrar.
timeout /t %WAIT_SECONDS% /nobreak >nul
goto nova_rodada
