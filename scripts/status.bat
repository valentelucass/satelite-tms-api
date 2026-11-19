@echo off
setlocal EnableExtensions
node "%~dp0..\deploy\clientes\operar.cjs" status todos
exit /b %errorlevel%
