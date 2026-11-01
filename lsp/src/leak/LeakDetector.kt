package org.example.kotlineditor.lsp.leak

import kotlin.math.roundToLong

/** One sample of the private memory of a language server's process tree. */
data class MemoryPoint(
    /** Milliseconds since the epoch when the sample was taken. */
    val timeMillis: Long,
    /** Private memory in bytes at that moment. */
    val bytes: Long,
)

/** Activity counters for one period: the requests and edits the server handled since the previous sample. */
data class Activity(
    /** Milliseconds since the epoch that end the period these counters cover. */
    val timeMillis: Long,
    /** Requests received during the period. */
    val requests: Int,
    /** Edits received during the period. */
    val edits: Int,
)

/** Thresholds that tune [evaluate]; each has a default, so a caller overrides only what it cares about. */
data class LeakSettings(
    /** Growing is declared when the slope exceeds this many bytes per minute. */
    val minSlopeBytesPerMinute: Long = DEFAULT_MIN_SLOPE_BYTES_PER_MINUTE,
    /** Growing is also declared when the slope exceeds this fraction of the baseline per minute. */
    val minSlopeRatioPerMinute: Double = DEFAULT_MIN_SLOPE_RATIO_PER_MINUTE,
    /** Number of minute minimums the Theil-Sen slope is estimated over. */
    val windowMinutes: Int = DEFAULT_WINDOW_MINUTES,
    /** A suspected leak needs at least this many non-decreasing minutes of the last [nonDecreasingWindow]. */
    val minNonDecreasing: Int = DEFAULT_MIN_NON_DECREASING,
    /** Number of minute-to-minute deltas inspected for the suspected-leak check. */
    val nonDecreasingWindow: Int = DEFAULT_NON_DECREASING_WINDOW,
    /** A suspected leak needs the latest minimum to be this much above the baseline. */
    val minGrowthRatio: Double = DEFAULT_MIN_GROWTH_RATIO,
    /** Memory is ignored until this long after startup and indexing have both finished. */
    val warmUpMillis: Long = DEFAULT_WARM_UP_MILLIS,
    /** Warning threshold in bytes, or null for no soft limit. */
    val softLimitBytes: Long? = null,
    /** Restart threshold in bytes, or null for no hard limit. */
    val hardLimitBytes: Long? = null,
)

/** What [evaluate] thinks of a memory history. */
enum class Verdict {
    /** Not enough data to decide, or still in the warm-up period. */
    NotEnoughData,

    /** Memory is not growing. */
    Normal,

    /** Memory is growing, but not yet steadily enough to call it a leak. */
    Growing,

    /** Memory is growing and has been for a while: probably a leak. */
    SuspectedLeak,

    /** Memory exceeded the hard limit. */
    OverLimit,
}

/** The outcome of [evaluate]: the verdict and the numbers behind it, for the UI. */
data class LeakReport(
    /** The verdict. */
    val verdict: Verdict,
    /** Theil-Sen slope in bytes per minute, or null when it was not computed. */
    val slopeBytesPerMinute: Double?,
    /** First minute minimum after the warm-up, in bytes, or null when it was not computed. */
    val baselineBytes: Long?,
    /** Growth since the baseline, in bytes (negative when memory shrank), or null when not computed. */
    val growthBytes: Long?,
    /** Whether the growth happened while the server handled no request or edit. */
    val idleGrowth: Boolean,
    /** Whether the latest sample exceeds [LeakSettings.softLimitBytes]. */
    val overSoftLimit: Boolean,
    /** Whether the latest sample exceeds [LeakSettings.hardLimitBytes]. */
    val overHardLimit: Boolean,
    /** One English sentence describing the verdict, for the UI. */
    val reason: String,
)

private const val MILLIS_PER_MINUTE = 60_000L

private const val DEFAULT_MIN_SLOPE_BYTES_PER_MINUTE = 2L * 1024 * 1024
private const val DEFAULT_MIN_SLOPE_RATIO_PER_MINUTE = 0.01
private const val DEFAULT_WINDOW_MINUTES = 15
private const val DEFAULT_MIN_NON_DECREASING = 12
private const val DEFAULT_NON_DECREASING_WINDOW = 14
private const val DEFAULT_MIN_GROWTH_RATIO = 0.25
private const val DEFAULT_WARM_UP_MILLIS = 2L * 60 * 1000

private const val KIB = 1024L
private const val MIB = 1024L * 1024
private const val GIB = 1024L * 1024 * 1024

private const val MIN_MINIMUMS = 5

/** One point of the lower envelope: the minimum memory of a whole minute. */
private data class EnvelopePoint(
    /** Minute since the epoch (millis / [MILLIS_PER_MINUTE]). */
    val minute: Long,
    /** Minimum private memory during that minute, in bytes. */
    val bytes: Long,
)

/**
 * Judges the memory history of a language server: normal, growing, probably leaking, or over its limit.
 *
 * Pure: it reads no clock and touches no process; everything arrives as a parameter, so it is fully unit tested.
 */
object LeakDetector {
    /**
     * Judges a memory history for leaks.
     *
     * The verdict is based on the lower envelope (the minimum of each whole minute after the warm-up), which hides
     * garbage collection sawtooth and short spikes. The Theil-Sen slope of the last [LeakSettings.windowMinutes]
     * minimums decides [Verdict.Growing], and a growing slope that is also steadily non-decreasing and well above the
     * baseline becomes [Verdict.SuspectedLeak]. Limits are checked on the latest sample even during the warm-up, and
     * a hard limit always wins. The parameters are many because they come from different parts of the supervisor.
     */
    @Suppress("LongParameterList")
    fun evaluate(
        points: List<MemoryPoint>,
        activity: List<Activity>,
        indexingEndedAt: Long?,
        startedAt: Long,
        now: Long,
        settings: LeakSettings,
    ): LeakReport {
        val samples =
            points
                .filter { it.timeMillis <= now }
                .sortedBy { it.timeMillis }
        val latestBytes = samples.lastOrNull()?.bytes
        val softLimit = settings.softLimitBytes
        val hardLimit = settings.hardLimitBytes
        val overSoft = softLimit != null && latestBytes != null && latestBytes > softLimit
        val warmUpEnd = maxOf(startedAt, indexingEndedAt ?: startedAt) + settings.warmUpMillis
        val minimums = envelope(samples, warmUpEnd)
        val overHard = hardLimit != null && latestBytes != null && latestBytes > hardLimit
        return when {
            overHard -> overLimit(checkNotNull(latestBytes), checkNotNull(hardLimit), overSoft)
            latestBytes == null -> notEnough(false, "No memory samples yet")
            now < warmUpEnd -> notEnough(overSoft, "Still in the warm-up period after startup or indexing")
            minimums.size < MIN_MINIMUMS -> notEnough(overSoft, "Not enough memory samples to detect a leak")
            else -> assess(minimums, activity, now, settings, overSoft)
        }
    }

    /** Serializes [points] as CSV for the diagnostic report: a header line, then one `time,bytes` row per sample. */
    fun toCsv(points: List<MemoryPoint>): String {
        val csv = StringBuilder("time_ms,private_bytes")
        for (point in points) {
            csv.append('\n')
            csv.append(point.timeMillis)
            csv.append(',')
            csv.append(point.bytes)
        }
        return csv.toString()
    }
}

private fun overLimit(latestBytes: Long, hardLimit: Long, overSoft: Boolean) =
    LeakReport(
        verdict = Verdict.OverLimit,
        slopeBytesPerMinute = null,
        baselineBytes = null,
        growthBytes = null,
        idleGrowth = false,
        overSoftLimit = overSoft,
        overHardLimit = true,
        reason = "Private memory ${formatBytes(latestBytes)} exceeds the hard limit of ${formatBytes(hardLimit)}",
    )

private fun notEnough(overSoft: Boolean, reason: String) =
    LeakReport(
        verdict = Verdict.NotEnoughData,
        slopeBytesPerMinute = null,
        baselineBytes = null,
        growthBytes = null,
        idleGrowth = false,
        overSoftLimit = overSoft,
        overHardLimit = false,
        reason = reason,
    )

// The lower envelope: the minimum of each whole minute after the warm-up; a minute without a sample is skipped.
private fun envelope(samples: List<MemoryPoint>, warmUpEnd: Long): List<EnvelopePoint> {
    val result = ArrayList<EnvelopePoint>()
    var minute = Long.MIN_VALUE
    var minBytes = Long.MAX_VALUE
    for (sample in samples) {
        if (sample.timeMillis < warmUpEnd) continue
        val current = sample.timeMillis / MILLIS_PER_MINUTE
        if (current != minute) {
            if (minute != Long.MIN_VALUE) result += EnvelopePoint(minute, minBytes)
            minute = current
            minBytes = sample.bytes
        } else if (sample.bytes < minBytes) {
            minBytes = sample.bytes
        }
    }
    if (minute != Long.MIN_VALUE) result += EnvelopePoint(minute, minBytes)
    return result
}

private fun assess(
    minimums: List<EnvelopePoint>,
    activity: List<Activity>,
    now: Long,
    settings: LeakSettings,
    overSoft: Boolean,
): LeakReport {
    val baseline = minimums.first().bytes
    val window = minimums.takeLast(settings.windowMinutes)
    val slope = theilSenSlope(window)
    val threshold =
        maxOf(settings.minSlopeBytesPerMinute.toDouble(), settings.minSlopeRatioPerMinute * baseline.toDouble())
    val growing = slope > threshold
    val idleGrowth = growing && isIdle(activity, now, settings.windowMinutes)
    val verdict =
        when {
            growing && suspectedLeak(minimums, baseline, settings) -> Verdict.SuspectedLeak
            growing -> Verdict.Growing
            else -> Verdict.Normal
        }
    val latest = window.last().bytes
    val reason = if (verdict == Verdict.Normal) "Memory is stable" else growthReason(window, slope, idleGrowth)
    return LeakReport(
        verdict = verdict,
        slopeBytesPerMinute = slope,
        baselineBytes = baseline,
        growthBytes = latest - baseline,
        idleGrowth = idleGrowth,
        overSoftLimit = overSoft,
        overHardLimit = false,
        reason = reason,
    )
}

// For example "+180 MB in 10 min while idle (18 MB/min)": the growth over the window the slope was measured on.
private fun growthReason(window: List<EnvelopePoint>, slope: Double, idle: Boolean): String {
    val span = (window.last().minute - window.first().minute).coerceAtLeast(1)
    val growth = window.last().bytes - window.first().bytes
    val growthText = if (growth >= 0) "+${formatBytes(growth)}" else formatBytes(growth)
    val idleText = if (idle) " while idle" else ""
    return "$growthText in $span min$idleText (${formatBytes(slope.roundToLong())}/min)"
}

// Theil-Sen: the median of every pairwise slope between the window's minute minimums (at most 105 pairs).
private fun theilSenSlope(window: List<EnvelopePoint>): Double {
    val slopes = DoubleArray(window.size * (window.size - 1) / 2)
    var count = 0
    for (i in window.indices) {
        for (j in i + 1 until window.size) {
            slopes[count++] = (window[j].bytes - window[i].bytes).toDouble() / (window[j].minute - window[i].minute)
        }
    }
    slopes.sort()
    val middle = slopes.size / 2
    return if (slopes.size % 2 == 1) slopes[middle] else (slopes[middle - 1] + slopes[middle]) / 2
}

// Whether the server handled no request or edit during the window the slope covers.
private fun isIdle(activity: List<Activity>, now: Long, windowMinutes: Int): Boolean {
    val windowStart = now - windowMinutes * MILLIS_PER_MINUTE
    return activity.none { it.timeMillis in windowStart..now && (it.requests > 0 || it.edits > 0) }
}

// The two extra conditions that turn Growing into SuspectedLeak.
private fun suspectedLeak(minimums: List<EnvelopePoint>, baseline: Long, settings: LeakSettings): Boolean {
    val deltas =
        minimums
            .zipWithNext { previous, next -> next.bytes - previous.bytes }
            .takeLast(settings.nonDecreasingWindow)
    val steady = deltas.size >= settings.nonDecreasingWindow && deltas.count { it >= 0 } >= settings.minNonDecreasing
    val grown = minimums.last().bytes.toDouble() >= baseline.toDouble() * (1 + settings.minGrowthRatio)
    return steady && grown
}

// Formats a byte count in the largest whole unit, e.g. "312 MB".
private fun formatBytes(bytes: Long): String {
    val magnitude = kotlin.math.abs(bytes)
    val (unit, name) =
        when {
            magnitude >= GIB -> GIB to "GB"
            magnitude >= MIB -> MIB to "MB"
            magnitude >= KIB -> KIB to "KB"
            else -> 1L to "B"
        }
    return "${(bytes.toDouble() / unit).roundToLong()} $name"
}
