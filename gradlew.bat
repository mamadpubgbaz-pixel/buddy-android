@echo off
set VERSION=9.8.2
where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
echo Gradle is not installed. Install Gradle 9.8.2 or use Android Studio to sync this project.
exit /b 1
