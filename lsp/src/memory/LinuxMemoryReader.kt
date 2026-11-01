package org.example.kotlineditor.lsp.memory

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.abs

/**
 * Reads process memory from `/proc`, one small file per fact: `status` for a sample, `stat` to check that a
 * pid is still the same process, `smaps_rollup` for the on-demand detail. Each read is a few µs on a warm
 * page cache; no `ps`, no shell, nothing that costs a process or a pipe per sample.
 *
 * A pid is compared through its start time before its memory is read, because Linux reuses pids: a language
 * server that crashed and a new process that took its pid would otherwise be summed as if nothing happened.
 */
class LinuxMemoryReader : ProcessMemoryReader {
    // The start time of this process, the reference every other start time is compared against. Both ends of
    // the comparison come from the JVM, so whatever boot time it used to turn ticks into an instant cancels
    // out and only the difference between the two processes remains.
    private val selfStart by lazy { readSelfStart() }

    override fun read(pid: Long, startInstant: Instant?): ProcessMemory? {
        if (startInstant != null && !isSameProcess(pid, startInstant)) return null
        return memoryOf(pid)
    }

    override fun detail(pid: Long): Map<String, Long> = proc(pid, "smaps_rollup")?.let(::parseSmapsRollup) ?: emptyMap()

    override fun release(pid: Long) {
        // /proc keeps no per-process resource open.
    }

    override fun close() {
        // /proc keeps no per-process resource open.
    }

    // Whether the process behind [pid] still is the one that started at [startInstant].
    private fun isSameProcess(pid: Long, startInstant: Instant): Boolean {
        val ticks = proc(pid, "stat")?.let(::parseProcStatStartTime) ?: return false
        val expected =
            selfStart.ticks + (startInstant.toEpochMilli() - selfStart.epochMillis) / MILLIS_PER_TICK
        // The JVM reports a start time as boot time plus ticks, so an instant difference is exactly the tick
        // difference; one tick of slack covers its rounding to milliseconds. A reused pid starts at least the
        // old process's lifetime later, which no sampled process comes close to.
        return abs(ticks - expected) <= START_TIME_SLACK_TICKS
    }

    private fun memoryOf(pid: Long): ProcessMemory? = proc(pid, "status")?.let(::parseProcStatus)

    // The start time of this process: its ticks, and the JVM's instant for the same.
    private fun readSelfStart(): SelfStart {
        val self = ProcessHandle.current()
        val ticks = proc(self.pid(), "stat")?.let(::parseProcStatStartTime)
        val instant = self.info().startInstant().orElse(null)
        check(ticks != null && instant != null) { "Cannot read the start time of this process from /proc" }
        return SelfStart(ticks, instant.toEpochMilli())
    }

    // The content of a file of `/proc/<pid>/`, or null when it cannot be read (the process is gone).
    private fun proc(pid: Long, file: String): ByteArray? =
        try {
            Files.readAllBytes(Path.of("/proc", pid.toString(), file))
        } catch (_: IOException) {
            // The process is gone or its /proc entry cannot be read: not an error, the caller skips it.
            null
        }

    /** This process's start time, both as ticks (the /proc unit) and as the JVM's instant. */
    private data class SelfStart(val ticks: Long, val epochMillis: Long)

    private companion object {
        /**
         * Clock ticks per second, the unit of the start times in `stat`: USER_HZ, fixed at 100 by the
         * kernel's userspace ABI on every architecture a JVM runs on (glibc's `sysconf(_SC_CLK_TCK)`
         * answers the same 100).
         */
        const val USER_HZ = 100L

        const val MILLIS_PER_TICK = 1000L / USER_HZ

        /** How far a start time may differ, in ticks, before the pid counts as another process. */
        const val START_TIME_SLACK_TICKS = 1L
    }
}
