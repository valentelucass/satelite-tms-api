@echo off
setlocal EnableExtensions

pm2 describe Satelite-API-19090 | findstr /I "online" >nul
if not errorlevel 1 (
    echo [OK] Satelite-API-19090 ja esta online.
    exit /b 0
)

call "%~dp0validar_api_dashboard.bat"
if errorlevel 1 exit /b %errorlevel%

pm2 start ecosystem.config.js --only Satelite-API-19090
if errorlevel 1 (
    echo [ERRO] Nao foi possivel iniciar Satelite-API-19090.
    exit /b 1
)

echo [OK] API Dashboard iniciada na porta 19090 em modo somente leitura.
exit /b 0
