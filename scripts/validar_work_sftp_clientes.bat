@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
call "%~dp0_common.bat" validate-db
if errorlevel 1 exit /b 1
if /I not "%SFTP_CLIENTS_ENABLED%"=="true" (
    echo [ERRO] SFTP_CLIENTS_ENABLED deve ser true.
    exit /b 1
)
if "%SFTP_CLIENTS_IDS%"=="" (
    echo [ERRO] SFTP_CLIENTS_IDS nao configurado.
    exit /b 1
)
if /I not "%VEDACIT_SFTP_RECEIPT_ONLY%"=="true" (
    echo [ERRO] VEDACIT_SFTP_RECEIPT_ONLY deve ser true.
    exit /b 1
)
node -e "const a=require('./deploy/clientes/ecosystem.config.js').apps.find(p=>p.name==='clientes' && p.namespace==='satelite-tms');if(!a)process.exit(1);console.log(a.namespace+'/'+a.name)"
exit /b %errorlevel%
