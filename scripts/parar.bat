@echo off
setlocal EnableExtensions
node "%~dp0..\deploy\clientes\operar.cjs" parar todos
exit /b %errorlevel%
