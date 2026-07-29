@echo off
setlocal EnableExtensions

cd /d "%~dp0.."
call "%~dp0_common.bat"

set "RETENTION_MAX_FILES=%LOG_RETENTION_MAX_FILES%"
set "RETENTION_MAX_AGE_DAYS=%LOG_RETENTION_MAX_AGE_DAYS%"
set "RETENTION_MAX_TOTAL_MB=%LOG_RETENTION_MAX_TOTAL_MB%"
if "%RETENTION_MAX_FILES%"=="" set "RETENTION_MAX_FILES=20"
if "%RETENTION_MAX_AGE_DAYS%"=="" set "RETENTION_MAX_AGE_DAYS=30"
if "%RETENTION_MAX_TOTAL_MB%"=="" set "RETENTION_MAX_TOTAL_MB=500"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0rotacionar_logs.ps1" ^
  -LogsDirectory "%LOGS_DIR%" ^
  -MaxFiles %RETENTION_MAX_FILES% ^
  -MaxAgeDays %RETENTION_MAX_AGE_DAYS% ^
  -MaxTotalMb %RETENTION_MAX_TOTAL_MB%
exit /b %ERRORLEVEL%
