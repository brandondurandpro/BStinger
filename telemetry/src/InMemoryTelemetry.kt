package org.example.kotlineditor.telemetry

import java.util.concurrent.CopyOnWriteArrayList

/** A span as it ended. */
data class FinishedSpan(
    val operation: Operation,
    val attributes: Attributes,
    val context: SpanContext,
    val parent: SpanContext?,
    val side: Side,
    val durationNanos: Long,
    val outcome: Outcome,
)

/** Keeps the spans that ended, in order: the tests' telemetry. */
class InMemoryHook : TelemetryHook {
    private val finished = CopyOnWriteArrayList<FinishedSpan>()

    /** The spans that ended so far. */
    val spans: List<FinishedSpan> get() = finished.toList()

    override fun onEnd(span: Span, durationNanos: Long, outcome: Outcome, attributes: Attributes) {
        finished += FinishedSpan(span.operation, attributes, span.context, span.parent, span.side, durationNanos, outcome)
    }

    /** Forgets them. */
    fun clear() = finished.clear()
}

/** Telemetry recording its spans in memory, for the tests. */
class InMemoryTelemetry(side: Side = Side.Client, private val hook: InMemoryHook = InMemoryHook()) :
    HookedTelemetry(listOf(hook), side) {
    /** The spans that ended so far. */
    val spans: List<FinishedSpan> get() = hook.spans

    /** The ended spans of [operation]. */
    fun spansOf(operation: Operation): List<FinishedSpan> = spans.filter { it.operation == operation }

    /** Forgets them. */
    fun clear() = hook.clear()
}
