@echo off
setlocal

cd /d "%~dp0"

set "MAVEN_CMD=mvn"
if exist "%~dp0mvnw.cmd" (
    set "MAVEN_CMD=%~dp0mvnw.cmd"
)

echo.
echo =========================================
echo  Satelite TMS - build limpo e confiavel
echo =========================================
echo.

echo [1/2] Parando instancia anterior do Satelite, se existir...
call scripts\parar.bat
if errorlevel 1 goto stop_failed

echo.
echo [2/2] Limpando, gerando SOAP e empacotando sem testes...
call "%MAVEN_CMD%" clean package "-Dmaven.test.skip=true" "-Dmaven.jaxws.skip=false"
if errorlevel 1 goto build_failed

echo.
echo =========================================
echo  Build concluido com sucesso.
echo =========================================
echo.
pause
exit /b 0

:stop_failed
echo.
echo =========================================
echo  Falha ao parar instancia anterior.
echo  Feche o Satelite em execucao e tente novamente.
echo =========================================
echo.
pause
exit /b 1

:build_failed
echo.
echo =========================================
echo  Falha no build. Verifique o log acima.
echo =========================================
echo.
pause
exit /b 1
