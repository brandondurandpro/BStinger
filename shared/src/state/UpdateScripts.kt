package org.example.kotlineditor.state

import java.io.File

/**
 * The script of the swap for Unix: POSIX `/bin/sh`, so that it runs under dash. Every path is single-quoted, a quote
 * of a path closed, escaped and reopened.
 */
internal object UnixSwapScript {
    // Single quotes: nothing inside is interpreted; a quote in a path is closed, escaped and reopened.
    private fun quoted(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    fun build(job: SwapJob): String = listOf(unixHeader(job), unixWait(), unixSwap()).joinToString("\n")

    private fun unixHeader(job: SwapJob): String {
        val relaunch = job.launcher.joinToString(" ") { quoted(it) }
        return """
        |#!/bin/sh
        |# Installs the update of Kotlin Editor once the IDE has exited. Generated; deletes itself.
        |trap '' HUP
        |pid=${job.pid}
        |installed=${quoted(job.installed.path)}
        |staged=${quoted(job.staged.path)}
        |backup=${quoted(job.backup.path)}
        |log=${quoted(File(job.directory, UpdateSwap.LOG_NAME).path)}
        |waits=${job.waitSeconds}
        |
        |say() { echo "$(date '+%Y-%m-%d %H:%M:%S') ${'$'}1" >> "${'$'}log"; }
        |relaunch() { ( trap - HUP; exec $relaunch </dev/null >/dev/null 2>&1 ) & }
        |# Nothing was installed: the old IDE starts again, and the script goes.
        |giveUp() { say "${'$'}1"; relaunch; rm -f "${'$'}0"; exit 1; }
        |
        """.trimMargin()
    }

    private fun unixWait(): String =
        """
        |while kill -0 "${'$'}pid" 2>/dev/null; do
        |  if [ "${'$'}waits" -le 0 ]; then
        |    say "the IDE (pid ${'$'}pid) is still running, the update is not installed"
        |    exit 1
        |  fi
        |  waits=${'$'}((waits - 1))
        |  sleep 1
        |done
        |
        """.trimMargin()

    // The swap: the old install moves aside, the new one moves in, and the old one comes back if that fails.
    private fun unixSwap(): String =
        """
        |[ -e "${'$'}staged" ] || giveUp "nothing to install: ${'$'}staged does not exist"
        |rm -rf "${'$'}backup"
        |[ ! -e "${'$'}backup" ] || giveUp "cannot remove the old backup ${'$'}backup"
        |mv "${'$'}installed" "${'$'}backup" || giveUp "cannot move ${'$'}installed aside"
        |if ! mv "${'$'}staged" "${'$'}installed"; then
        |  mv "${'$'}backup" "${'$'}installed"
        |  giveUp "cannot move the new version into place, the old one is back"
        |fi
        |say "updated ${'$'}installed"
        |relaunch
        |rm -rf "${'$'}backup"
        |rm -f "${'$'}0"
        |
        """.trimMargin()
}

/**
 * The script of the swap for Windows: a `cmd.exe` batch file (no PowerShell; the waits are `ping`s because `timeout`
 * fails without a console), with the lines ended by CRLF. Every path is in quotes in a `set "NAME=value"`, and the
 * percent signs of a path are doubled, as the batch language expands them even inside quotes.
 */
internal object WindowsSwapScript {
    // Batch files expand %...% even inside quotes: a percent sign of a path is doubled.
    private fun batch(text: String): String = text.replace("%", "%%")

    fun build(job: SwapJob): String {
        val relaunch = job.launcher.joinToString(" ") { "\"${batch(it)}\"" }
        val parts = listOf(windowsHeader(job), windowsWait(), windowsSwap(), windowsEnd(relaunch))
        return parts.joinToString("\n").replace("\n", "\r\n")
    }

    private fun windowsHeader(job: SwapJob): String {
        val log = File(job.directory, UpdateSwap.LOG_NAME).path
        return """
        |@echo off
        |rem Installs the update of Kotlin Editor once the IDE has exited. Generated; deletes itself.
        |set "PID=${job.pid}"
        |set "INSTALLED=${batch(job.installed.path)}"
        |set "STAGED=${batch(job.staged.path)}"
        |set "BACKUP=${batch(job.backup.path)}"
        |set "LOG=${batch(log)}"
        |set /a WAITS=${job.waitSeconds}
        |set /a TRIES=15
        |
        """.trimMargin()
    }

    // The ends of the script, each starting the IDE (the new one, or the old one when nothing was installed).
    private fun windowsEnd(relaunch: String): String =
        """
        |:swapped
        |echo %DATE% %TIME% updated "%INSTALLED%">>"%LOG%"
        |start "" $relaunch
        |if exist "%BACKUP%" rmdir /s /q "%BACKUP%"
        |goto finish
        |
        |:missing
        |echo %DATE% %TIME% nothing to install: "%STAGED%" does not exist>>"%LOG%"
        |goto relaunchOld
        |
        |:failedAside
        |echo %DATE% %TIME% cannot move "%INSTALLED%" aside>>"%LOG%"
        |
        |:relaunchOld
        |start "" $relaunch
        |
        |:finish
        |(goto) 2>nul & del "%~f0"
        |
        """.trimMargin()

    private fun windowsWait(): String =
        """
        |:wait
        |tasklist /FI "PID eq %PID%" /NH 2>nul | find " %PID% " >nul
        |if errorlevel 1 goto exited
        |if %WAITS% LEQ 0 goto stillRunning
        |set /a WAITS-=1
        |ping -n 2 127.0.0.1 >nul
        |goto wait
        |
        |:stillRunning
        |echo %DATE% %TIME% the IDE (pid %PID%) is still running, the update is not installed>>"%LOG%"
        |exit /b 1
        |
        """.trimMargin()

    // The swap: the old install moves aside (retried: a process of the old IDE may still hold a file), the new one
    // moves in, and the old one comes back if that fails.
    private fun windowsSwap(): String =
        """
        |:exited
        |if not exist "%STAGED%" goto missing
        |
        |:aside
        |if exist "%BACKUP%" rmdir /s /q "%BACKUP%"
        |if exist "%BACKUP%" goto retryAside
        |move "%INSTALLED%" "%BACKUP%" >nul 2>>"%LOG%"
        |if not errorlevel 1 goto inPlace
        |:retryAside
        |set /a TRIES-=1
        |if %TRIES% LEQ 0 goto failedAside
        |ping -n 3 127.0.0.1 >nul
        |goto aside
        |
        |:inPlace
        |move "%STAGED%" "%INSTALLED%" >nul 2>>"%LOG%"
        |if not errorlevel 1 goto swapped
        |echo %DATE% %TIME% cannot move the new version into place, putting the old one back>>"%LOG%"
        |move "%BACKUP%" "%INSTALLED%" >nul 2>>"%LOG%"
        |goto relaunchOld
        |
        """.trimMargin()
}
