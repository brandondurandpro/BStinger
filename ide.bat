@echo off
rem Opens the IDE on a folder, from any directory (Windows):  ide [folder]   (the current directory by default)
rem Add the folder of this file to the PATH. Linux and macOS: the `ide` script.
setlocal
set "FOLDER=%~1"
if "%FOLDER%"=="" set "FOLDER=%CD%"
pushd "%FOLDER%" 2>nul || (
    echo ide: not a directory: %FOLDER% 1>&2
    exit /b 2
)
set "FOLDER=%CD%"
popd
rem A trailing backslash (C:\) would escape the closing quote of the argument.
if "%FOLDER:~-1%"=="\" set "FOLDER=%FOLDER%."
cd /d "%~dp0"
start "" /b cmd /c ""%~dp0kotlin.bat" run -m desktopApp -- --open "%FOLDER%" > "%TEMP%\kotlin-editor-ide.log" 2>&1"
