@echo off
rem Windows version of `run-tests`, which says why the UI modules are tested one at a time.
rem With arguments, runs `kotlin.bat test` with them as they are.
setlocal enabledelayedexpansion
cd /d "%~dp0"
if not "%~1"=="" (
    call kotlin.bat test %*
    exit /b !errorlevel!
)
set excludes=
for /f "eol=# tokens=*" %%m in (tools\ui-test-modules.txt) do set excludes=!excludes! --exclude-module %%m
set failed=
echo run-tests: modules without UI tests
call kotlin.bat test %excludes%
if errorlevel 1 set failed=!failed! modules-without-UI-tests
for /f "eol=# tokens=*" %%m in (tools\ui-test-modules.txt) do (
    echo run-tests: %%m
    call kotlin.bat test -m %%m
    if errorlevel 1 set failed=!failed! %%m
)
if not "!failed!"=="" (
    echo run-tests: failed:!failed! 1>&2
    exit /b 1
)
exit /b 0
