package org.example.kotlineditor.telemetry

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

// The frame that shows a new state is the second one: the first recomposes, the second is the one drawn after it.
private const val FRAMES_TO_SHOW = 2

/**
 * Ends [span] once the user sees its result: after the frames that draw the state it changed. Without a frame clock
 * (code run off the UI), it ends now.
 */
suspend fun Telemetry.endAtFrame(span: Span?, outcome: Outcome = Outcome.Ok, attributes: Attributes = Attributes.NONE) {
    if (span == null) return
    val hasFrames = coroutineContext[MonotonicFrameClock] != null
    try {
        if (hasFrames) repeat(FRAMES_TO_SHOW) { withFrameNanos {} }
        end(span, outcome, attributes)
    } catch (cancelled: CancellationException) {
        end(span, Outcome.Cancelled, attributes)
        throw cancelled
    }
}

/**
 * Measures the time the user perceives of [block]: from now until the frame that shows what it did. The calls to the
 * server made by [block] are children of its span. [attributesOf] describes the result (its size, its outcome).
 */
suspend fun <T> Telemetry.measureToFrame(
    operation: Operation,
    attributes: Attributes = Attributes.NONE,
    attributesOf: (T) -> Attributes = { Attributes.NONE },
    outcomeOf: (T) -> Outcome = { Outcome.Ok },
    block: suspend () -> T,
): T {
    if (!isEnabled) return block()
    val span = start(operation, attributes, CurrentSpan.ofCoroutine()?.context)
    val result =
        try {
            if (span == null) block() else withContext(SpanElement(span)) { block() }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // measured, then thrown on
            end(span, Outcome.of(failure))
            throw failure
        }
    endAtFrame(span, outcomeOf(result), attributesOf(result))
    return result
}

/** The outcome of a call whose failure was caught ([runCatching]): its error, or [Outcome.Ok]. */
fun Result<*>.outcome(): Outcome {
    val failure = exceptionOrNull() ?: return Outcome.Ok
    return Outcome.of(failure)
}

/** The size bucket of [count] items (files, results, lines). */
fun sizeOf(count: Int): Attributes = Attributes.of(Attribute.Size to sizeBucket(count.toLong()))
