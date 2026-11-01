package org.example.kotlineditor.lsp.leak

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MB = 1024L * 1024
private const val GB = 1024 * MB
private const val START = 1_800_000_000_000L
private const val SAMPLE_MS = 2_000L
private const val MINUTE_MS = 60_000L

// Private memory sampled every 2 seconds for `minutes`, as the sampler records it.
private fun trace(minutes: Int, bytesAt: (seconds: Double) -> Long): List<MemoryPoint> =
    (0 until (minutes * MINUTE_MS / SAMPLE_MS).toInt()).map { index ->
        val time = START + index * SAMPLE_MS
        MemoryPoint(time, bytesAt((time - START) / 1000.0))
    }

private fun List<MemoryPoint>.end() = last().timeMillis

private fun evaluate(
    points: List<MemoryPoint>,
    activity: List<Activity> = emptyList(),
    indexingEndedAt: Long? = null,
    now: Long = points.end(),
    settings: LeakSettings = LeakSettings(),
) = LeakDetector.evaluate(points, activity, indexingEndedAt, START, now, settings)

// Activity every minute of the trace: the server is in use.
private fun busy(points: List<MemoryPoint>) =
    (START..points.end() step MINUTE_MS).map { Activity(it, requests = 3, edits = 5) }

/** Verdicts on synthetic traces shaped like real language servers. */
class LeakDetectorTest {
    private val base = 400 * MB

    @Test
    fun `steady linear growth is a suspected leak`() {
        val points = trace(30) { base + (5 * MB * it / 60).toLong() }
        val report = evaluate(points, busy(points))
        assertEquals(Verdict.SuspectedLeak, report.verdict, report.reason)
        assertEquals(5.0 * MB, report.slopeBytesPerMinute!!, 0.2 * MB)
        assertFalse(report.idleGrowth)
        assertTrue(report.growthBytes!! > 100 * MB)
    }

    @Test
    fun `garbage collection sawtooth on a flat base stays normal`() {
        for (periodSeconds in listOf(30.0, 90.0, 180.0)) {
            // Grows by 300 MB then drops back, as a JVM or Node heap between two collections.
            val points = trace(40) { base + (300 * MB * (it % periodSeconds / periodSeconds)).toLong() }
            val report = evaluate(points, busy(points))
            assertEquals(Verdict.Normal, report.verdict, "period $periodSeconds s: ${report.reason}")
        }
    }

    @Test
    fun `indexing spike then plateau stays normal`() {
        val indexingEnd = START + 5 * MINUTE_MS
        val points = trace(40) { if (it < 300) 2 * GB else 600 * MB }
        val report = evaluate(points, busy(points), indexingEndedAt = indexingEnd)
        assertEquals(Verdict.Normal, report.verdict, report.reason)
    }

    @Test
    fun `a single spike stays normal`() {
        val points = trace(30) { if (it in 900.0..940.0) base + GB else base }
        assertEquals(Verdict.Normal, evaluate(points, busy(points)).verdict)
    }

    @Test
    fun `small noise around a flat base stays normal`() {
        val points = trace(30) { base + (20 * MB * sin(it * 2 * PI / 47)).toLong() }
        assertEquals(Verdict.Normal, evaluate(points, busy(points)).verdict)
    }

    @Test
    fun `growth below the threshold stays normal`() {
        // 1 MB/min on 400 MB: below both 2 MB/min and 1 % of the baseline per minute.
        val points = trace(40) { base + (MB * it / 60).toLong() }
        assertEquals(Verdict.Normal, evaluate(points, busy(points)).verdict)
    }

    @Test
    fun `growth that is not steady enough is only growing`() {
        // Fast rises and partial drops: the slope is high but too many minutes decrease.
        val points = trace(30) { base + (8 * MB * it / 60).toLong() + if ((it / 60).toInt() % 2 == 0) 0 else 40 * MB }
        val report = evaluate(points, busy(points))
        assertEquals(Verdict.Growing, report.verdict, report.reason)
    }

    @Test
    fun `growth that stops goes back to normal once the window has passed`() {
        val leakEnd = 20 * 60.0
        val points = trace(60) { base + (10 * MB * minOf(it, leakEnd) / 60).toLong() }
        val during = evaluate(points, busy(points), now = START + 20 * MINUTE_MS)
        assertEquals(Verdict.SuspectedLeak, during.verdict, during.reason)
        assertEquals(Verdict.Normal, evaluate(points, busy(points)).verdict)
    }

    @Test
    fun `growth without any activity is reported as idle growth`() {
        val points = trace(30) { base + (18 * MB * it / 60).toLong() }
        val report = evaluate(points)
        assertEquals(Verdict.SuspectedLeak, report.verdict)
        assertTrue(report.idleGrowth)
        assertTrue(report.reason.contains("while idle"), report.reason)
        assertTrue(report.reason.contains("18 MB/min"), report.reason)
    }

    @Test
    fun `gaps in the samples are tolerated`() {
        // The sampler missed whole minutes (laptop asleep): the remaining minutes still show the leak.
        val points =
            trace(40) { base + (6 * MB * it / 60).toLong() }.filterNot { (it.timeMillis - START) / MINUTE_MS % 4 == 1L }
        val report = evaluate(points, busy(points))
        assertEquals(Verdict.SuspectedLeak, report.verdict, report.reason)
        assertEquals(6.0 * MB, report.slopeBytesPerMinute!!, 0.2 * MB)
    }

    @Test
    fun `fewer than five minutes after the warm-up is not enough data`() {
        val points = trace(6) { base + (50 * MB * it / 60).toLong() }
        assertEquals(Verdict.NotEnoughData, evaluate(points).verdict)
        assertEquals(Verdict.NotEnoughData, evaluate(emptyList(), now = START).verdict)
    }

    @Test
    fun `the warm-up after startup and after indexing is ignored`() {
        // Memory climbs while indexing, then is flat: only the flat part may be judged.
        val indexingEnd = START + 10 * MINUTE_MS
        val points = trace(30) { if (it < 600) base + (40 * MB * it / 60).toLong() else base + 400 * MB }
        assertEquals(Verdict.Normal, evaluate(points, busy(points), indexingEndedAt = indexingEnd).verdict)
        val early = evaluate(points, busy(points), indexingEndedAt = indexingEnd, now = START + 11 * MINUTE_MS)
        assertEquals(Verdict.NotEnoughData, early.verdict)
    }

    @Test
    fun `there is no memory limit by default`() {
        val points = trace(30) { 64 * GB }
        val report = evaluate(points)
        assertFalse(report.overSoftLimit)
        assertFalse(report.overHardLimit)
        assertEquals(Verdict.Normal, report.verdict)
    }

    @Test
    fun `soft and hard limits are checked even during the warm-up`() {
        val points = trace(1) { 3 * GB }
        val soft = evaluate(points, settings = LeakSettings(softLimitBytes = 2 * GB))
        assertTrue(soft.overSoftLimit)
        assertEquals(Verdict.NotEnoughData, soft.verdict)
        val hard = evaluate(points, settings = LeakSettings(softLimitBytes = 2 * GB, hardLimitBytes = 2 * GB))
        assertEquals(Verdict.OverLimit, hard.verdict)
        assertTrue(hard.overHardLimit)
        assertTrue(hard.reason.contains("hard limit"), hard.reason)
    }

    @Test
    fun `memory history is exported as CSV`() {
        val csv = LeakDetector.toCsv(listOf(MemoryPoint(1, 10), MemoryPoint(3, 30)))
        assertEquals("time_ms,private_bytes\n1,10\n3,30", csv)
    }

    @Test
    fun `one hour of history is judged in well under a millisecond`() {
        val points = trace(60) { base + (5 * MB * it / 60).toLong() }
        val activity = busy(points)
        repeat(200) { evaluate(points, activity) }
        val runs = 1_000
        val started = System.nanoTime()
        repeat(runs) { evaluate(points, activity) }
        val averageMicros = (System.nanoTime() - started) / runs / 1_000.0
        println("PERF LeakDetector.evaluate on ${points.size} points: %.1f µs".format(averageMicros))
        // Budget several times the measured cost, so that a slow CI runner passes but a quadratic scan fails.
        assertTrue(averageMicros < 1_000, "evaluate took $averageMicros µs")
    }
}
