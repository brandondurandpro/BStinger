# Watches a test run on Windows (issue #120): once it has run for $Minutes, it saves the threads of every JVM
# (jcmd Thread.print, from the JDK of that JVM) and the processes started since the watch began, then stops the JVMs,
# so that a hung run fails with its log instead of freezing the job. A second dump, two minutes after the first, tells
# a hang (same stacks) from a slow run.
param(
    [Parameter(Mandatory)] [int] $Minutes,
    [Parameter(Mandatory)] [string] $OutDir
)
$ErrorActionPreference = 'Continue'
New-Item -ItemType Directory -Force $OutDir | Out-Null
$started = Get-Date

function Save-Dump([string] $label) {
    $processes = Get-CimInstance Win32_Process | Where-Object { $_.CreationDate -ge $started }
    $processes |
        ForEach-Object { '{0} {1}<{2} {3} {4}' -f $_.Name, $_.ProcessId, $_.ParentProcessId, $_.CreationDate.ToString('HH:mm:ss'), $_.CommandLine } |
        Set-Content (Join-Path $OutDir "processes-$label.txt")
    foreach ($java in @($processes | Where-Object { $_.Name -eq 'java.exe' })) {
        $jcmd = Join-Path (Split-Path $java.ExecutablePath) 'jcmd.exe'
        $file = Join-Path $OutDir "threads-$label-$($java.ProcessId).txt"
        if (Test-Path $jcmd) {
            & $jcmd $java.ProcessId Thread.print *> $file
        } else {
            Set-Content $file "no jcmd next to $($java.ExecutablePath)"
        }
    }
}

Start-Sleep -Seconds ($Minutes * 60)
Save-Dump 'first'
Start-Sleep -Seconds 120
Save-Dump 'second'
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CreationDate -ge $started } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Set-Content (Join-Path $OutDir 'stopped.txt') "Stopped the JVMs after $Minutes + 2 minutes, at $(Get-Date -Format o)"
