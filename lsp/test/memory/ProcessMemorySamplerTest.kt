package org.example.kotlineditor.lsp.memory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val MB = 1024L * 1024
private const val HOG_MB = 192

/** The memory of real process trees, read on the OS the tests run on (Linux and Windows). */
class ProcessMemorySamplerTest {
    private val processes = mutableListOf<Process>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reader = ProcessMemoryReader.forCurrentOs()
    private val supported = reader !is UnsupportedMemoryReader

    @AfterTest
    fun cleanup() {
        scope.cancel()
        processes.forEach(MemoryHog::stop)
        reader.close()
    }

    private fun hog(megabytes: Int, childMegabytes: Int? = null) =
        MemoryHog.start(megabytes, childMegabytes).also { processes += it }

    private fun sampler(reader: ProcessMemoryReader = this.reader) =
        ProcessMemorySampler(reader, System::currentTimeMillis, scope, intervalMs = 50, treeRefreshMs = 100)

    @Test
    fun `the private memory a process holds is read`() {
        if (!supported) return
        val idle = hog(0)
        val busy = hog(HOG_MB)
        val idleMemory =
            assertNotNull(
                reader.read(
                    idle.pid(),
                    idle
                        .toHandle()
                        .info()
                        .startInstant()
                        .orElse(null),
                ),
            )
        val busyMemory =
            assertNotNull(
                reader.read(
                    busy.pid(),
                    busy
                        .toHandle()
                        .info()
                        .startInstant()
                        .orElse(null),
                ),
            )
        val difference = busyMemory.privateBytes - idleMemory.privateBytes
        assertTrue(difference in HOG_MB * MB * 8 / 10..HOG_MB * MB * 3 / 2, "difference ${difference / MB} MB")
        // Not compared to the private memory: on Windows the commit charge can exceed the working set.
        assertTrue(busyMemory.residentBytes >= HOG_MB * MB * 8 / 10, "resident ${busyMemory.residentBytes / MB} MB")
    }

    @Test
    fun `the detail breakdown is read on demand`() {
        if (!supported) return
        val process = hog(16)
        val detail = reader.detail(process.pid())
        assertTrue(detail.isNotEmpty())
        assertTrue(detail.values.all { it >= 0 }, detail.toString())
    }

    @Test
    fun `the children of a tracked process are counted`() {
        if (!supported) return
        val parent = hog(0, childMegabytes = HOG_MB)
        val history = sampler().track("php", parent.toHandle())
        val sample = awaitSample(history) { it.processCount == 2 }
        assertTrue(sample.privateBytes >= HOG_MB * MB * 8 / 10, "tree holds ${sample.privateBytes / MB} MB")
    }

    @Test
    fun `a tree whose root exits is dropped`() {
        if (!supported) return
        val process = hog(0)
        val sampler = sampler()
        val history = sampler.track("php", process.toHandle())
        awaitSample(history) { true }
        MemoryHog.stop(process)
        awaitCondition("tree dropped") { !sampler.isTracked("php") }
        assertNull(reader.read(process.pid(), null))
    }

    @Test
    fun `a pid that another process took is not read as the old one`() {
        if (!supported || reader !is LinuxMemoryReader) return
        // On Windows the open handle pins the process, so a pid cannot be reused while it is sampled.
        val process = hog(0)
        val start =
            process
                .toHandle()
                .info()
                .startInstant()
                .orElseThrow()
        assertNotNull(reader.read(process.pid(), start))
        assertNull(reader.read(process.pid(), start.minusSeconds(3_600)))
    }

    @Test
    fun `a failing reader stops neither its own tree nor the others`() {
        val broken = hog(0)
        // Not the test JVM itself: the broken hog is one of its children, so its tree would fail too.
        val other = hog(0)
        val calls = AtomicInteger()
        val failing =
            object : ProcessMemoryReader {
                override fun read(pid: Long, startInstant: Instant?): ProcessMemory? {
                    calls.incrementAndGet()
                    if (pid == broken.pid()) throw IllegalStateException("cannot read")
                    return ProcessMemory(1, 2)
                }

                override fun detail(pid: Long) = emptyMap<String, Long>()

                override fun release(pid: Long) = Unit

                override fun close() = Unit
            }
        val sampler = sampler(failing)
        val healthy = sampler.track("healthy", other.toHandle())
        sampler.track("broken", broken.toHandle())
        awaitSample(healthy) { healthy.size >= 5 }
        assertTrue(sampler.isTracked("broken"))
        val before = calls.get()
        awaitCondition("broken tree retried") { calls.get() > before + 4 }
        assertTrue(sampler.isTracked("broken"))
    }

    @Test
    fun `tracking a key again starts a new history`() {
        val sampler = sampler(FixedReader)
        val first = sampler.track("php", ProcessHandle.current())
        awaitSample(first) { true }
        val second = sampler.track("php", ProcessHandle.current())
        assertTrue(second !== first)
        awaitSample(second) { true }
        sampler.untrack("php")
        assertFalse(sampler.isTracked("php"))
    }

    @Test
    fun `the server samples its own memory`() {
        val sample = sampler().sampleSelf()
        assertTrue(sample.privateBytes > 0)
        if (supported) assertTrue(sample.residentBytes > 0)
    }

    @Test
    fun `reading ten processes costs well under a millisecond`() {
        if (!supported) return
        val pids =
            List(10) { hog(0) }.map {
                it.pid() to
                    it
                        .toHandle()
                        .info()
                        .startInstant()
                        .orElse(null)
            }
        repeat(100) { pids.forEach { (pid, start) -> reader.read(pid, start) } }
        val runs = 200
        val started = System.nanoTime()
        repeat(runs) { pids.forEach { (pid, start) -> assertNotNull(reader.read(pid, start)) } }
        val micros = (System.nanoTime() - started) / runs / 1_000.0
        println("PERF one sample of 10 processes: %.1f µs".format(micros))
        // Budget several times the measured cost: a shell command per sample would cost tens of milliseconds.
        assertTrue(micros < 1_000, "one sample of 10 processes took $micros µs")
    }

    private fun awaitSample(history: MemoryHistory, condition: (MemorySample) -> Boolean): MemorySample {
        var found: MemorySample? = null
        awaitCondition("sample") { history.latest?.takeIf(condition)?.also { found = it } != null }
        return checkNotNull(found)
    }

    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for $what")
            Thread.onSpinWait()
            Thread.yield()
        }
    }

    private object FixedReader : ProcessMemoryReader {
        override fun read(pid: Long, startInstant: Instant?) = ProcessMemory(1, 2)

        override fun detail(pid: Long) = emptyMap<String, Long>()

        override fun release(pid: Long) = Unit

        override fun close() = Unit
    }
}
