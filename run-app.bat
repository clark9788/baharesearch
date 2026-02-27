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

set "JAVA_EXE=%~dp0runtime\bin\java.exe"
if not exist "%JAVA_EXE%" (
  set "JAVA_EXE=java"
)

"%JAVA_EXE%" -jar "%~dp0target\BahaiResearch-1.0.0-SNAPSHOT-all.jar"
