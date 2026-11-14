package org.example.kotlineditor.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SECOND = 1_000_000_000L
private const val BURN_LIMIT_NANOS = 60 * SECOND
private const val BURN_BATCH = 100_000

class CpuMeterTest {
    private var cpu: Long? = 0L
    private var now = 10 * SECOND

    private fun meter(minWindow: Long = SECOND) = CpuMeter({ cpu }, { now }, minWindow)

    // Lets [seconds] pass during which the process used [busy] seconds of CPU.
    private fun elapse(seconds: Long, busy: Long) {
        now += seconds * SECOND
        cpu = (cpu ?: 0L) + busy * SECOND
    }

    @Test
    fun `the first call only takes a reference`() {
        assertNull(meter().percent())
    }

    @Test
    fun `a process busy half of the time reads 50`() {
        val meter = meter()
        meter.percent()
        elapse(seconds = 2, busy = 1)
        assertEquals(50, meter.percent())
    }

    @Test
    fun `two cores busy read 200`() {
        val meter = meter()
        meter.percent()
        elapse(seconds = 2, busy = 4)
        assertEquals(200, meter.percent())
    }

    @Test
    fun `an idle process reads 0`() {
        val meter = meter()
        meter.percent()
        elapse(seconds = 2, busy = 0)
        assertEquals(0, meter.percent())
    }

    @Test
    fun `each measure covers the time since the previous one`() {
        val meter = meter()
        meter.percent()
        elapse(seconds = 2, busy = 2)
        assertEquals(100, meter.percent())
        elapse(seconds = 2, busy = 0)
        assertEquals(0, meter.percent())
    }

    @Test
    fun `a call too soon answers the last measure and keeps the reference`() {
        val meter = meter()
        meter.percent()
        elapse(seconds = 2, busy = 1)
        assertEquals(50, meter.percent())
        // 0.5 s later: too short to be a rate, the 50 stays.
        now += SECOND / 2
        cpu = cpu!! + SECOND / 2
        assertEquals(50, meter.percent())
        // The half second is not lost: the next measure covers it.
        elapse(seconds = 1, busy = 0)
        assertEquals(33, meter.percent())
    }

    @Test
    fun `a call too soon after the reference answers null`() {
        val meter = meter()
        meter.percent()
        elapse(seconds = 0, busy = 0)
        assertNull(meter.percent())
    }

    @Test
    fun `a counter that went backwards starts over`() {
        val meter = meter()
        meter.percent()
        elapse(seconds = 2, busy = 1)
        meter.percent()
        cpu = 0L
        now += 2 * SECOND
        assertNull(meter.percent())
        elapse(seconds = 2, busy = 1)
        assertEquals(50, meter.percent())
    }

    @Test
    fun `a process whose CPU cannot be read answers null, then measures again once it can`() {
        val meter = meter()
        meter.percent()
        cpu = null
        now += 2 * SECOND
        assertNull(meter.percent())
        cpu = 5 * SECOND
        assertNull(meter.percent())
        elapse(seconds = 2, busy = 1)
        assertEquals(50, meter.percent())
    }

    @Test
    fun `a reader that throws answers null`() {
        val meter = CpuMeter({ error("denied") }, { now })
        assertNull(meter.percent())
        assertNull(meter.percent())
    }

    // Keeps a thread busy until this process used [cpuNanos] of processor and [wallNanos] went by. How long that
    // takes depends on the machine: the runners of the CI run the tests of several modules side by side, on few cores.
    private fun burn(cpuNanos: Long, wallNanos: Long): Long {
        val handle = ProcessHandle.current()
        val cpuAtStart = handle.info().totalCpuDuration().orElse(null)?.toNanos() ?: return 0L
        val start = System.nanoTime()
        val giveUpAt = start + BURN_LIMIT_NANOS
        var sink = 0L
        while (System.nanoTime() < giveUpAt) {
            repeat(BURN_BATCH) { sink += sink xor System.nanoTime() }
            val cpuNow = handle.info().totalCpuDuration().orElse(null)?.toNanos() ?: cpuAtStart
            val usedEnough = cpuNow - cpuAtStart >= cpuNanos
            val longEnough = System.nanoTime() - start >= wallNanos
            if (usedEnough && longEnough) break
        }
        return sink
    }

    @Test
    fun `the running process is measured by the JVM, whatever the OS`() {
        val meter = CpuMeter.ofCurrentProcess()
        // Linux, Windows and macOS all tell the CPU time of a process; a null is a failure of this code.
        assertNull(meter.percent())
        val sink = burn(cpuNanos = SECOND / 2, wallNanos = SECOND + SECOND / 5)
        val percent = meter.percent()
        assertNotNull(percent, "no CPU time for this process (sink $sink)")
        // Not a share of a core: a busy runner gives this thread whatever it has left.
        assertTrue(percent > 0, "half a second of processor reads $percent%")
    }
}
