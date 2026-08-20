@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
call "%~dp0validar_work_sftp_clientes.bat"
if errorlevel 1 exit /b 1
pm2 describe WORK-SFTP-CLIENTES | findstr /I "online launching" >nul
if not errorlevel 1 echo [ERRO] WORK-SFTP-CLIENTES ja esta em execucao.& exit /b 1
pm2 start ecosystem.config.js --only WORK-SFTP-CLIENTES
