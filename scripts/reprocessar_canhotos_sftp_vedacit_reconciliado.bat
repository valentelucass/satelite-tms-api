@echo off
setlocal EnableExtensions

chcp 65001 >nul
cd /d "%~dp0.."

echo.
echo =========================================================
echo  Lote SFTP Vedacit com reconciliacao de CT-e
echo =========================================================
echo Usa o CT-e do FTP, consulta o XML oficial apenas para classificar
echo e envia uma unica vez. Execute antes a opcao M e valide a previa.
echo.
choice /c SN /n /m "A previa foi validada e o envio foi autorizado? [S/N]"
if errorlevel 2 exit /b 1

set "VEDACIT_SFTP_RECONCILIATION_ENABLED=true"
call "%~dp0reprocessar_canhotos_sftp_vedacit.bat" %~1
exit /b %ERRORLEVEL%
