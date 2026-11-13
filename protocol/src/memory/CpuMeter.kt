package org.example.kotlineditor.memory

import java.time.Duration

private const val NANOS_PER_SECOND = 1_000_000_000L

private const val PERCENT = 100.0

/**
 * The processor time a process uses, as a percentage of one core (like `top`: a process busy on two cores reads
 * 200). It turns the cumulative CPU time the OS keeps for the process into a rate, between two of its calls.
 *
 * [cpuNanos] is that cumulative time, null when the OS does not tell it. The JVM's own [ProcessHandle] gives it on
 * Linux, Windows and macOS alike, so no reader per OS is needed ([ofCurrentProcess]).
 *
 * The first call only takes a reference and answers null. A call less than [minWindowNanos] after the previous
 * measure answers that measure again instead of a rate over a few milliseconds, which would read as noise (the OS
 * counts CPU time in ticks of 10 ms or more). A counter that went backwards (the process was replaced, the OS
 * reset it) starts over from the next call. Never throws.
 */
class CpuMeter(
    // The cumulative CPU time of the process, in nanoseconds; null when it cannot be read.
    private val cpuNanos: () -> Long?,
    // A monotonic clock in nanoseconds; `System::nanoTime` in production, a controllable clock in tests.
    private val clockNanos: () -> Long = System::nanoTime,
    // The shortest time a rate is measured over.
    private val minWindowNanos: Long = NANOS_PER_SECOND,
) {
    private var referenceCpu = NO_REFERENCE
    private var referenceAt = 0L
    private var lastPercent: Int? = null

    /**
     * The CPU use since the previous measure, rounded to a whole percent; null before there is a reference and
     * wherever the OS does not tell the CPU time of the process.
     */
    @Synchronized
    fun percent(): Int? {
        val cpu = readCpu()
        val now = clockNanos()
        return if (cpu == null) forget() else measure(cpu, now)
    }

    private fun measure(cpu: Long, now: Long): Int? {
        val window = now - referenceAt
        val used = cpu - referenceCpu
        return when {
            referenceCpu == NO_REFERENCE -> startFrom(cpu, now)
            window < minWindowNanos -> lastPercent
            used < 0 -> startFrom(cpu, now)
            else -> rateOver(cpu, now, used, window)
        }
    }

    private fun rateOver(cpu: Long, now: Long, used: Long, window: Long): Int {
        val percent = Math.round(used * PERCENT / window).toInt()
        referenceCpu = cpu
        referenceAt = now
        lastPercent = percent
        return percent
    }

    private fun startFrom(cpu: Long, now: Long): Int? {
        referenceCpu = cpu
        referenceAt = now
        lastPercent = null
        return null
    }

    private fun forget(): Int? {
        referenceCpu = NO_REFERENCE
        lastPercent = null
        return null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readCpu(): Long? =
        try {
            cpuNanos()
        } catch (_: RuntimeException) {
            null
        }

    /** The meters of processes. */
    companion object {
        private const val NO_REFERENCE = -1L

        /**
         * The meter of the running process: its CPU time comes from the JVM, whatever the OS. A longer
         * [minWindowNanos] smooths the rate: Windows counts CPU time in steps of 15.6 ms.
         */
        fun ofCurrentProcess(minWindowNanos: Long = NANOS_PER_SECOND): CpuMeter =
            CpuMeter(::currentProcessCpuNanos, minWindowNanos = minWindowNanos)

        // The CPU time of this process, or null when the OS does not tell it.
        private fun currentProcessCpuNanos(): Long? {
            val info = ProcessHandle.current().info()
            val total: Duration? = info.totalCpuDuration().orElse(null)
            return total?.toNanos()
        }
    }
}
