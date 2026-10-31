@echo off
rem Run by dockur/windows at the end of the unattended installation: see setup.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File C:\OEM\setup.ps1 > C:\OEM\setup.log 2>&1
