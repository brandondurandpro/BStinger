@echo off
rem Windows version of `run-tests`, which says why the UI modules are tested one at a time and what the steps are.
rem With arguments other than --only, runs `kotlin.bat test` with them as they are.
rem `run-tests.bat --only "non-ui,agent-client.1"` runs only the steps listed (quoted: cmd splits arguments at commas).
setlocal enabledelayedexpansion
cd /d "%~dp0"
set only=
if /i "%~1"=="--only" (
    if "%~2"=="" (
        echo run-tests: --only needs the steps to run, separated by commas 1>&2
        exit /b 2
    )
    set "only=%~2"
    shift
    shift
)
if not "%~1"=="" (
    call kotlin.bat test %*
    exit /b !errorlevel!
)
set excludes=
for /f "eol=# tokens=*" %%m in (tools\ui-test-modules.txt) do set excludes=!excludes! --exclude-module %%m
set failed=
if "!only!"=="" (
    call :step non-ui
    for /f "eol=# tokens=*" %%m in (tools\ui-test-modules.txt) do call :step %%m
) else (
    set "only=!only:,= !"
    for %%s in (!only!) do call :step %%s
)
if not "!failed!"=="" (
    echo run-tests: failed:!failed! 1>&2
    exit /b 1
)
exit /b 0

rem Runs a step: non-ui, a UI module, or a shard of a module (<module>.<shard>, see tools\ci\test-shards.txt), and adds
rem it to `failed` when it fails.
:step
echo run-tests: %~1
set "module=%~1"
set "shard="
for /f "tokens=1,2 delims=." %%a in ("%~1") do (
    set "module=%%a"
    set "shard=%%b"
)
if "!module!"=="non-ui" (
    call kotlin.bat test !excludes!
    if errorlevel 1 set failed=!failed! %~1
    exit /b 0
)
set classes=
if not "!shard!"=="" (
    rem The classes of the shard, or, for `rest`, every class that no line of the table names.
    for /f "eol=# tokens=1-3" %%a in (tools\ci\test-shards.txt) do (
        if "%%a"=="!module!" (
            if "!shard!"=="rest" (
                set classes=!classes! --exclude-classes=%%c
            ) else if "%%b"=="!shard!" (
                set classes=!classes! --include-classes=%%c
            )
        )
    )
    if "!classes!"=="" (
        echo run-tests: unknown shard '%~1' ^(see tools\ci\test-shards.txt^) 1>&2
        set failed=!failed! %~1
        exit /b 0
    )
)
call kotlin.bat test -m !module! !classes!
if errorlevel 1 set failed=!failed! %~1
exit /b 0
