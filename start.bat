@echo off
setlocal enabledelayedexpansion
title SigninHub Backend

set "HERE=%~dp0"
set "BACKEND=%HERE%backend"

echo ==================================================
echo    SigninHub - auto check-in backend
echo ==================================================
echo.

set "PYEXE="

set "BUNDLED=%USERPROFILE%\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
if exist "%BUNDLED%" call :probe "%BUNDLED%"

if not defined PYEXE ( where py >nul 2>nul && call :probe "py" )
if not defined PYEXE ( where python >nul 2>nul && call :probe "python" )
if not defined PYEXE ( where python3 >nul 2>nul && call :probe "python3" )

if not defined PYEXE (
  echo [ERROR] No usable Python 3 found on this machine.
  echo         Install Python 3.9+ from python.org and tick "Add python.exe to PATH",
  echo         then run this file again.
  echo         Note: the Microsoft Store python stub does not work here.
  echo.
  pause
  exit /b 1
)

echo [OK] Python   : !PYEXE!
echo [OK] Backend  : %BACKEND%
echo [OK] Console  : http://127.0.0.1:8787/
echo.
echo Starting... (close this window to stop)
echo.

pushd "%BACKEND%"
"!PYEXE!" run.py
set "RC=!errorlevel!"
popd

echo.
echo [SigninHub stopped] exit code = !RC!
pause
exit /b !RC!

:probe
set "CAND=%~1"
set "MARK=%TEMP%\signhub_pyok.tmp"
if exist "%MARK%" del "%MARK%" >nul 2>nul
"%CAND%" -c "import os;open(os.environ['MARK'],'w').write('ok')" >nul 2>nul
if exist "%MARK%" (
  set "PYEXE=%CAND%"
  del "%MARK%" >nul 2>nul
)
exit /b 0