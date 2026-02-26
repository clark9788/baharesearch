@echo off
setlocal

if "%~1"=="" (
  echo Usage: run-app.bat ^<full-path-to-keys.properties^>
  exit /b 1
)

set "KEY_PATH=%~1"

if not exist "%KEY_PATH%" (
  echo KEY_PATH file not found: %KEY_PATH%
  exit /b 1
)

java -jar "%~dp0target\BahaiResearch-1.0.0-SNAPSHOT-all.jar"
