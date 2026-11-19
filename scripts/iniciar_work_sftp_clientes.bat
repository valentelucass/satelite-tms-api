@echo off
setlocal EnableExtensions
node "%~dp0..\deploy\clientes\operar.cjs" iniciar clientes
exit /b %errorlevel%
