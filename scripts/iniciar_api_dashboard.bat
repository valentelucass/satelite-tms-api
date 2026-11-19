@echo off
setlocal EnableExtensions
node "%~dp0..\deploy\clientes\operar.cjs" iniciar api-integracoes-19090
exit /b %errorlevel%
