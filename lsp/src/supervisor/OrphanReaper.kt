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

        /**
         * Kills [process] and every process it started; the children are listed first, while they are its own.
         * [start] is its start time, read while it ran: needed to find its children once it exited.
         */
        fun killTree(process: ProcessHandle, start: Long = startOf(process)) {
            val children = ownDescendants(process, start)
            process.destroyForcibly()
            children.forEach { it.destroyForcibly() }
        }

        /**
         * The processes [process] started, [start] being its start time (0: unknown, none is returned).
         *
         * [ProcessHandle.descendants] goes by parent pid. Once a process exited, the JDK no longer finds its start
         * time and stops comparing start times, and Windows still names a parent pid that has since been reused:
         * the descendants of an exited process are then every process whose long dead parent had the same pid, a
         * CI runner for instance, and everything those started since, the Git of another program included. The
         * tree is walked here instead (see [ownTree]): a process is taken only when the one that leads to it is,
         * and none once the pid belongs to another process.
         */
        fun ownDescendants(process: ProcessHandle, start: Long): List<ProcessHandle> {
            val reused = ProcessHandle.of(process.pid()).map { startOf(it) != start }.orElse(false)
            if (start == 0L || reused) return emptyList()
            return ownTree(process, start, ::childrenOf, ::startOf)
        }

        private fun childrenOf(process: ProcessHandle): List<ProcessHandle> =
            runCatching { process.children().use { it.toList() } }.getOrDefault(emptyList())
    }
}

/**
 * The processes below [root], which started at [rootStart], [childrenOf] naming the processes whose parent pid is
 * the pid of a process and [startOf] their start time (0: unknown).
 *
 * A process is its parent's only when it started after it: one that started before was started by a dead process
 * that had the same pid. Such a process is left out with everything below it, however recent: a process started a
 * moment ago by a stranger is not ours because it is young.
 */
internal fun <P> ownTree(root: P, rootStart: Long, childrenOf: (P) -> List<P>, startOf: (P) -> Long): List<P> {
    val own = LinkedHashSet<P>()
    val parents = ArrayDeque<Pair<P, Long>>()
    parents.addLast(root to rootStart)
    while (parents.isNotEmpty()) {
        val (parent, parentStart) = parents.removeFirst()
        for (child in childrenOf(parent)) {
            val childStart = startOf(child)
            val startedByParent = childStart != 0L && childStart >= parentStart
            val isNew = child != root && child !in own
            if (!startedByParent || !isNew) continue
            own += child
            parents.addLast(child to childStart)
        }
    }
    return own.toList()
}
