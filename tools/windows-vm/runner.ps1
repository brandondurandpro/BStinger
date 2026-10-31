# Test runner of the Windows VM (see ../../windows-vm), started at logon from the shared folder. It waits for a
# request written by `./windows-vm test`: two lines, the run id and the arguments of `kotlin.bat test`. Then it
# checks the snapshot bundle out in C:\work\kotlin-editor (the build directory is kept between runs), runs the tests
# and writes their output to run\<id>.log and their exit code to run\<id>.exit.
param([string]$Share = '\\host.lan\Data')
$ErrorActionPreference = 'Stop'

$run = Join-Path $Share 'run'
$work = 'C:\work\kotlin-editor'
$env:Path = "C:\Program Files\Git\cmd;$env:Path"
$host.UI.RawUI.WindowTitle = 'kotlin-editor test runner'

# One cmd script does the checkout and the tests, so the whole log is written by cmd, in one encoding.
$job = Join-Path $env:TEMP 'kotlin-editor-job.cmd'
$lines = @(
    '@echo off'
    'call :job > "%JOB_LOG%" 2>&1'
    'exit /b %errorlevel%'
    ':job'
    'if not exist "%JOB_WORK%\.git" git init -q "%JOB_WORK%" || exit /b 1'
    'cd /d "%JOB_WORK%" || exit /b 1'
    'git fetch -q "%JOB_BUNDLE%" refs/windows-vm/snapshot || exit /b 1'
    'git checkout -q -f --detach FETCH_HEAD || exit /b 1'
    'git clean -q -fd'
    'echo Testing %JOB_COMMIT% on %COMPUTERNAME%'
    'call kotlin.bat test %JOB_ARGS%'
    'exit /b %errorlevel%'
)
[IO.File]::WriteAllText($job, ($lines -join "`r`n") + "`r`n")

# The runner's own log: an error must leave a trace (and must not stop the runner).
$runnerLog = Join-Path $Share 'runner.log'
function Write-Log([string]$message) { Add-Content $runnerLog "$((Get-Date).ToUniversalTime().ToString('s'))Z $message" }

New-Item -ItemType Directory -Force $run | Out-Null
Write-Log "runner started, waiting for requests in $run"
Set-Content (Join-Path $Share 'runner-ready') (Get-Date -Format o)

while ($true) {
    $request = Join-Path $run 'request'
    $id = $null
    try {
        # The SMB client caches whether a file exists: right after its removal, the request can still seem to be
        # there for a few seconds. A request that cannot be read is no request.
        $lines = if (Test-Path $request) { @(Get-Content $request -ErrorAction SilentlyContinue) } else { @() }
        if ($lines.Count -gt 0) {
            Remove-Item $request
            $id = $lines[0]
            $env:JOB_ARGS = if ($lines.Count -gt 1) { $lines[1] } else { '' }
            $env:JOB_LOG = Join-Path $run "$id.log"
            $env:JOB_WORK = $work
            $env:JOB_BUNDLE = Join-Path $run 'snapshot.bundle'
            $env:JOB_COMMIT = $id
            # Only the last runs are kept.
            Get-ChildItem $run -File | Where-Object { $_.Extension -in '.log', '.exit' } |
                Sort-Object LastWriteTime -Descending | Select-Object -Skip 10 | Remove-Item
            Write-Log "run $id`: kotlin.bat test $env:JOB_ARGS"
            $process = Start-Process cmd.exe -ArgumentList '/c', "`"$job`"" -Wait -PassThru -WindowStyle Minimized
            Set-Content (Join-Path $run "$id.exit") $process.ExitCode
            Write-Log "run $id exited with $($process.ExitCode)"
        }
    } catch {
        Write-Log "error: $_ $($_.InvocationInfo.PositionMessage)"
        # The request still gets an answer, so ./windows-vm test does not wait for nothing.
        if ($id) {
            Add-Content (Join-Path $run "$id.log") "windows-vm runner error: $_"
            Set-Content (Join-Path $run "$id.exit") 1
        }
    }
    Start-Sleep -Seconds 1
}
