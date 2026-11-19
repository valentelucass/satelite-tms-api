@echo off
setlocal EnableExtensions
node "%~dp0..\deploy\clientes\operar.cjs" parar clientes
exit /b %errorlevel%
