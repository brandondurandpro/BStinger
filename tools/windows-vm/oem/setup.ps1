# Prepares the Windows test VM once, at the end of its installation (see ../../../windows-vm): installs Git for
# Windows like on the CI image, keeps Defender off the build directories, and starts the test runner at each logon.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# Git for Windows: the server's Git support and its tests run `git`. Its installer also sets core.autocrlf=true,
# so the checkout gets CRLF line endings as on the CI.
$gitUrl = 'https://github.com/git-for-windows/git/releases/download/v2.51.0.windows.1/Git-2.51.0-64-bit.exe'
$gitSha256 = '843037416371600A7F289BE8FE2B2224AFE1C1BB0736BBAB7B3FF393E6A7AAF2'
$installer = Join-Path $env:TEMP 'Git-64-bit.exe'
Invoke-WebRequest $gitUrl -OutFile $installer -UseBasicParsing
if ((Get-FileHash $installer -Algorithm SHA256).Hash -ne $gitSha256) { throw 'Checksum mismatch for the Git installer' }
Start-Process $installer -ArgumentList '/VERYSILENT', '/NORESTART', '/NOCANCEL', '/SP-', '/SUPPRESSMSGBOXES' -Wait
Remove-Item $installer

# Real-time scanning of every class file and jar makes builds several times slower.
Add-MpPreference -ExclusionPath 'C:\work', 'C:\Users'

# A test VM: the screen never turns off and the session never locks under the tests.
powercfg /change monitor-timeout-ac 0
powercfg /change standby-timeout-ac 0

# The runner lives in the shared folder (refreshed by `./windows-vm start`): wait for it, then start it minimized
# in the logged-in desktop session, where the Compose tests can open windows, as on the CI. The folder is reached by
# its UNC path: after a reboot the Z: drive dockur maps stays unavailable until Explorer reconnects it.
$launcher = @(
    '@echo off'
    'rem Starts the kotlin-editor test runner of the shared folder, once the folder is reachable.'
    'for /l %%i in (1,1,120) do ('
    '    if exist "\\host.lan\Data\runner.ps1" goto found'
    '    timeout /t 5 /nobreak >nul'
    ')'
    'exit /b 1'
    ':found'
    'start "kotlin-editor runner" /min powershell -NoProfile -ExecutionPolicy Bypass -File "\\host.lan\Data\runner.ps1"'
)
$startup = Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\StartUp\kotlin-editor-runner.cmd'
[IO.File]::WriteAllText($startup, ($launcher -join "`r`n") + "`r`n")
