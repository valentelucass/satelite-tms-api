@echo off
setlocal EnableExtensions
node "%~dp0..\deploy\clientes\operar.cjs" parar SATELITE-TMS-CLIENTES
exit /b %errorlevel%
