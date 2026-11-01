package org.example.kotlineditor.lsp.memory

import java.time.Instant

/**
 * Reads the memory of a process the cheap way: one small system call or /proc read, no child process, no
 * shell command (`ps` and friends cost a process and 20 to 200 ms each, once per process per sample).
 *
 * [read] returns null when the process is gone, when another process reused its pid, or when it cannot be
 * read: the caller then skips it and the UI shows "n/a" rather than a wrong number. [startInstant] is the
 * process's start time, as the JVM reports it, recorded when the caller registered the process: a reader that
 * can check the identity of a pid uses it to detect a reuse (Linux does; Windows does not need to, it keeps
 * a process handle open, which pins the process the pid points to).
 *
 * [detail] is the heavier breakdown, read on demand when the memory window is open, never while sampling.
 *
 * A reader may keep one resource per process (the Windows handle): [release] drops the resource of a process
 * the caller no longer samples, and [close] drops them all. Both are cheap and never throw.
 */
interface ProcessMemoryReader : AutoCloseable {
    /**
     * The memory of [pid] now, or null if the process is gone, another process reused its pid, or it cannot
     * be read.
     */
    fun read(pid: Long, startInstant: Instant?): ProcessMemory?

    /** A breakdown of the memory of [pid] by name, in bytes; empty when it cannot be read. */
    fun detail(pid: Long): Map<String, Long>

    /** Drops any per-process resource kept for [pid]; a no-op when there is none. */
    fun release(pid: Long)

    /** Drops the per-process resources of every process; never throws. */
    override fun close()

    /** The reader of the current OS. */
    companion object {
        /**
         * The reader of the OS the server runs on: /proc on Linux, kernel32 on Windows, and
         * [UnsupportedMemoryReader] elsewhere, where memory reads as "n/a" and the leak detection stays off
         * rather than showing wrong numbers.
         */
        fun forCurrentOs(): ProcessMemoryReader {
            val os = System.getProperty("os.name")
            return when {
                os.startsWith("Linux") -> LinuxMemoryReader()
                os.startsWith("Windows", ignoreCase = true) -> WindowsMemoryReader()
                else -> UnsupportedMemoryReader
            }
        }
    }
}

/**
 * The reader of an OS whose processes cannot be read this way (macOS later, through `proc_pid_rusage`): every
 * process reads as "n/a".
 */
object UnsupportedMemoryReader : ProcessMemoryReader {
    override fun read(pid: Long, startInstant: Instant?): ProcessMemory? = null

    override fun detail(pid: Long): Map<String, Long> = emptyMap()

    override fun release(pid: Long) {
        // Nothing was ever opened, there is nothing to drop.
    }

    override fun close() {
        // Nothing was ever opened, there is nothing to drop.
    }
}
