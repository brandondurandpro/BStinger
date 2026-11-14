package org.example.kotlineditor.files

import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val REPLACE_ATTEMPTS = 40
private const val REPLACE_RETRY_MS = 50L
private val WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * Moves [part] onto [target], atomically where the file system can. Windows refuses to replace a file while a
 * program has it open, even to read it (an antivirus, a backup, another window of the IDE reading its settings):
 * the reader is gone a moment later, so the move is tried again for about two seconds instead of losing the write.
 * Blocking.
 */
fun replaceFile(part: Path, target: Path) {
    var attempt = 1
    while (true) {
        try {
            moveOnto(part, target)
            return
        } catch (denied: AccessDeniedException) {
            val lastAttempt = attempt == REPLACE_ATTEMPTS
            if (!WINDOWS || lastAttempt) throw denied
            attempt++
            Thread.sleep(REPLACE_RETRY_MS)
        }
    }
}

private fun moveOnto(part: Path, target: Path) {
    try {
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
