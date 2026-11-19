@echo off
setlocal EnableExtensions
node "%~dp0..\deploy\clientes\operar.cjs" iniciar SATELITE-TMS-CLIENTES
exit /b %errorlevel%
