package org.example.kotlineditor.telemetry

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Which process measures: the IDE or its server (the `side` label). */
enum class Side(val label: String) {
    Client("client"),
    Server("server"),
}

/** The W3C trace context of a span: a 128-bit trace id ([traceHigh], [traceLow]) and a 64-bit [spanId]. */
data class SpanContext(val traceHigh: Long, val traceLow: Long, val spanId: Long) {
    /** The trace id, 32 hexadecimal digits. */
    val traceId: String get() = hex(traceHigh) + hex(traceLow)

    /** The span id, 16 hexadecimal digits. */
    val spanIdHex: String get() = hex(spanId)

    /** The `traceparent` header of this context (version 00, sampled). */
    fun traceparent(): String = "00-$traceId-$spanIdHex-01"

    /** A new span of the same trace. */
    fun child(): SpanContext = SpanContext(traceHigh, traceLow, randomNonZero())

    companion object {
        private const val TRACEPARENT_LENGTH = 55
        private const val TRACE_START = 3
        private const val SPAN_START = 36
        private const val HALF_TRACE = 16

        /** A new trace. */
        fun root(): SpanContext = SpanContext(randomNonZero(), randomNonZero(), randomNonZero())

        /** The context of a `traceparent` header; null when it is malformed (it is then ignored). */
        fun parse(traceparent: String?): SpanContext? {
            val header = traceparent?.takeIf { it.length == TRACEPARENT_LENGTH && it.startsWith("00-") } ?: return null
            val high = header.substring(TRACE_START, TRACE_START + HALF_TRACE).toULongOrNull(HEX)?.toLong()
            val low = header.substring(TRACE_START + HALF_TRACE, SPAN_START - 1).toULongOrNull(HEX)?.toLong()
            val span = header.substring(SPAN_START, SPAN_START + HALF_TRACE).toULongOrNull(HEX)?.toLong()
            val valid = high != null && low != null && span != null && (high != 0L || low != 0L) && span != 0L
            return if (valid) SpanContext(high, low, span) else null
        }
    }
}

private const val HEX = 16

private fun hex(value: Long): String = value.toULong().toString(HEX).padStart(HEX, '0')

private fun randomNonZero(): Long {
    var value = 0L
    while (value == 0L) value = ThreadLocalRandom.current().nextLong()
    return value
}

/** An operation being measured, from [Telemetry.start] to [Telemetry.end]. */
class Span internal constructor(
    /** What is measured. */
    val operation: Operation,
    /** Its attributes; more can be given when it ends. */
    val attributes: Attributes,
    /** Its trace context. */
    val context: SpanContext,
    /** The span it is part of, in this process or the other one; null for the start of a trace. */
    val parent: SpanContext?,
    /** The process measuring it. */
    val side: Side,
    /** [System.nanoTime] when it started. */
    val startNanos: Long,
    /** Wall clock time it started, in nanoseconds since the epoch (for the export). */
    val startEpochNanos: Long,
) {
    private val ended = AtomicBoolean(false)

    // True the first time only: a span ends once.
    internal fun markEnded(): Boolean = ended.compareAndSet(false, true)
}

/** Told when a measured operation starts and ends: an export, a test's record. */
interface TelemetryHook {
    /** [span] started. */
    fun onStart(span: Span) {}

    /** [span] ended after [durationNanos] with [outcome]; [attributes] are those it started with, and those it ended with. */
    fun onEnd(span: Span, durationNanos: Long, outcome: Outcome, attributes: Attributes)

    /** Telemetry was switched off: what is queued can be dropped. */
    fun onDisabled() {}
}
