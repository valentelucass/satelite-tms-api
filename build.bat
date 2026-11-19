@echo off
setlocal EnableExtensions
cd /d "%~dp0"
if not defined JAVA_HOME if exist "%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-17\bin\java.exe" set "JAVA_HOME=%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-17"
set "MAVEN=%~dp0backend\java\mvnw.cmd"
for /f %%I in ('powershell.exe -NoProfile -Command "[Guid]::NewGuid().ToString([char]78)"') do set "BUILD_ID=%%I"
if not defined BUILD_ID exit /b 1
set "BUILD_DIR=%~dp0target\builds\%BUILD_ID%"
echo Compilando a API em "%BUILD_DIR%\api"...
call "%MAVEN%" -f "%~dp0backend\java\pom.xml" "-Dsatelite.build.directory=%BUILD_DIR%\api" -Dmaven.test.skip=true package
if errorlevel 1 exit /b 1
echo Compilando os clientes em "%BUILD_DIR%\clientes"...
call "%MAVEN%" -f "%~dp0backend\java\pom.xml" -Pclientes-worker "-Dsatelite.build.directory=%BUILD_DIR%\clientes" -Dmaven.test.skip=true package
if errorlevel 1 exit /b 1
echo Pacotes de compilacao em "%BUILD_DIR%".
exit /b 0
