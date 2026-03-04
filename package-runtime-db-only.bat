@echo off
setlocal

REM Build a DB-only runtime package (no curated source files).
REM Usage: package-runtime-db-only.bat [output-folder-name]

set "PACKAGE_NAME=%~1"
if "%PACKAGE_NAME%"=="" set "PACKAGE_NAME=BahaiResearch-runtime-db-only"

set "ROOT=%~dp0"
set "OUT=%ROOT%dist\db-only\%PACKAGE_NAME%"

echo [1/5] Preparing output folder: %OUT%
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"
mkdir "%OUT%\target"
mkdir "%OUT%\data"
mkdir "%OUT%\data\corpus"

echo [2/5] Copying runtime artifacts...
copy "%ROOT%target\BahaiResearch-1.0.0-SNAPSHOT-all.jar" "%OUT%\target\" >nul || goto :missingjar
copy "%ROOT%run-app.bat" "%OUT%\" >nul
copy "%ROOT%bahai-research.local-only.example.properties" "%OUT%\bahai-research.properties" >nul

REM Include private runtime if present
if exist "%ROOT%runtime\bin\java.exe" (
  xcopy "%ROOT%runtime" "%OUT%\runtime\" /E /I /Q /Y >nul
)

echo [3/5] Copying corpus DB...
copy "%ROOT%data\corpus\corpus.db" "%OUT%\data\corpus\" >nul || goto :missingdb

REM Include WAL/SHM if present (safe optional copy)
if exist "%ROOT%data\corpus\corpus.db-wal" copy "%ROOT%data\corpus\corpus.db-wal" "%OUT%\data\corpus\" >nul
if exist "%ROOT%data\corpus\corpus.db-shm" copy "%ROOT%data\corpus\corpus.db-shm" "%OUT%\data\corpus\" >nul

echo [4/5] Writing quick-start...
(
  echo BahaiResearch Runtime Package ^(DB-only^)
  echo.
  echo 1^) Open bahai-research.properties and add gemini.apiKey only if you want AI fallback.
  echo 2^) Default mode is local-only and works offline with corpus.db.
  echo 3^) If runtime\bin\java.exe exists, app uses bundled Java automatically.
  echo 4^) Run:
  echo    run-app.bat .\bahai-research.properties
) > "%OUT%\README-runtime.txt"

echo [5/5] Done.
echo Package created at:
echo %OUT%
exit /b 0

:missingjar
echo ERROR: Missing JAR at target\BahaiResearch-1.0.0-SNAPSHOT-all.jar
echo Build first: mvn -DskipTests package
exit /b 1

:missingdb
echo ERROR: Missing DB at data\corpus\corpus.db
echo Ensure corpus.db exists before packaging.
exit /b 1
