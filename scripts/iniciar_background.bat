@echo off
setlocal EnableExtensions
if not "%~1"=="" (
    echo Os servicos residentes agora sao api-integracoes-19090 e SATELITE-TMS-CLIENTES. Execute este arquivo sem argumentos para iniciar ambos.
    exit /b 2
)
node "%~dp0..\deploy\clientes\operar.cjs" iniciar todos
exit /b %errorlevel%
