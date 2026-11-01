package org.example.kotlineditor.lsp.supervisor

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Records the language server processes of this IDE server in [directory], so that the next IDE server can stop
 * the ones left behind by a crash or a `kill -9` (an orphan keeps its memory and may lock the index).
 *
 * One file per process, `<owner pid>-<owner start>-<pid>`, holding the process start time: a pid is only killed
 * when its start time still matches (pids are reused), and only when its owner is gone (two IDE servers of the same
 * user may run at once, each keeping its own servers).
 */
class OrphanReaper(private val directory: Path) {
    private val self = ProcessHandle.current()
    private val owner = "${self.pid()}-${startOf(self)}"

    /** Records [process], started by this IDE server. */
    fun record(process: ProcessHandle) {
        try {
            Files.createDirectories(directory)
            Files.writeString(fileOf(process.pid()), startOf(process).toString())
        } catch (_: IOException) {
            // Not recorded: the process is still stopped normally, only a crash of this server would leak it.
        }
    }

    /** Forgets [pid], stopped normally. */
    fun forget(pid: Long) {
        runCatching { Files.deleteIfExists(fileOf(pid)) }
    }

    /** Stops the processes recorded by IDE servers that are gone, and returns how many were still running. */
    fun reap(): Int {
        val files = runCatching { Files.list(directory).use { it.toList() } }.getOrDefault(emptyList())
        var reaped = 0
        for (file in files) {
            val parts = file.fileName.toString().split('-')
            val ownerPid = parts.getOrNull(0)?.toLongOrNull()
            val ownerStart = parts.getOrNull(1)?.toLongOrNull()
            val pid = parts.getOrNull(2)?.toLongOrNull()
            val ownerAlive =
                ownerPid != null && ProcessHandle.of(ownerPid).map { startOf(it) == ownerStart }.orElse(false)
            if (pid == null || ownerAlive) continue
            val start = runCatching { Files.readString(file).trim().toLong() }.getOrNull()
            val orphan = ProcessHandle.of(pid).filter { startOf(it) == start }
            if (orphan.isPresent) {
                killTree(orphan.get())
                reaped++
            }
            runCatching { Files.deleteIfExists(file) }
        }
        return reaped
    }

    private fun fileOf(pid: Long): Path = directory.resolve("$owner-$pid")

    /** Process helpers. */
    companion object {
        /** Start time of [process] in epoch milliseconds, 0 when the OS does not tell. */
        fun startOf(process: ProcessHandle): Long =
            process
                .info()
                .startInstant()
                .map(Instant::toEpochMilli)
                .orElse(0L)

        /** Kills [process] and every process it started; the children are listed first, while they are its own. */
        fun killTree(process: ProcessHandle) {
            val children = runCatching { process.descendants().use { it.toList() } }.getOrDefault(emptyList())
            process.destroyForcibly()
            children.forEach { it.destroyForcibly() }
        }
    }
}
