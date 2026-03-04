@echo off
setlocal

REM Build a private Java runtime image for BahaiResearch (Windows).
REM Usage: build-runtime-image.bat [runtime-folder-name]

set "RUNTIME_NAME=%~1"
if "%RUNTIME_NAME%"=="" set "RUNTIME_NAME=runtime"

set "ROOT=%~dp0"
set "OUT=%ROOT%%RUNTIME_NAME%"

where jlink >nul 2>&1
if errorlevel 1 (
  echo ERROR: jlink not found on PATH.
  echo Install JDK 21 and ensure jlink is available.
  exit /b 1
)

echo Removing existing runtime folder (if any): %OUT%
if exist "%OUT%" rmdir /s /q "%OUT%"

echo Building private runtime image...
jlink --add-modules java.base,java.desktop,java.logging,java.net.http,java.sql,jdk.unsupported,jdk.crypto.ec --output "%OUT%" --strip-debug --no-man-pages --no-header-files --compress=2
if errorlevel 1 (
  echo ERROR: jlink runtime build failed.
  exit /b 1
)

echo Runtime image created at:
echo %OUT%
exit /b 0
