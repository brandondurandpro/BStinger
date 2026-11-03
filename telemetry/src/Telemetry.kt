package org.example.kotlineditor.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Measures how long operations take. [Disabled] unless the user agreed: it then costs a null check and allocates
 * nothing. Spans nest: one started while another is current (on this thread, or in its coroutine) is its child.
 */
interface Telemetry {
    /** Whether spans are measured: callers skip building their attributes otherwise. */
    val isEnabled: Boolean

    /** Starts measuring [operation], as a child of [parent] or of the current span; null when disabled. */
    fun start(operation: Operation, attributes: Attributes = Attributes.NONE, parent: SpanContext? = null): Span?

    /** Ends [span] (nothing when null, or already ended) with [outcome] and more [attributes]. */
    fun end(span: Span?, outcome: Outcome = Outcome.Ok, attributes: Attributes = Attributes.NONE)

    /** An operation measured elsewhere, which took [durationNanos] (the first frame, a reconnection). */
    fun record(
        operation: Operation,
        durationNanos: Long,
        attributes: Attributes = Attributes.NONE,
        outcome: Outcome = Outcome.Ok,
    )

    /** Nothing is measured. */
    object Disabled : Telemetry {
        override val isEnabled = false

        override fun start(operation: Operation, attributes: Attributes, parent: SpanContext?): Span? = null

        override fun end(span: Span?, outcome: Outcome, attributes: Attributes) {}

        override fun record(operation: Operation, durationNanos: Long, attributes: Attributes, outcome: Outcome) {}
    }
}

/**
 * Measures with [hooks], on [side]. A hook that throws is skipped for that call: a broken export never breaks a
 * feature.
 */
open class HookedTelemetry(
    private val hooks: List<TelemetryHook>,
    private val side: Side,
    private val nanoTime: () -> Long = System::nanoTime,
    private val epochNanos: () -> Long = { System.currentTimeMillis() * NANOS_PER_MILLI },
) : Telemetry {
    override val isEnabled = true

    override fun start(operation: Operation, attributes: Attributes, parent: SpanContext?): Span {
        val from = parent ?: CurrentSpan.get()?.context
        val context = from?.child() ?: SpanContext.root()
        val span = Span(operation, attributes, context, from, side, nanoTime(), epochNanos())
        hooks.forEach { hook -> isolated { hook.onStart(span) } }
        return span
    }

    override fun end(span: Span?, outcome: Outcome, attributes: Attributes) {
        if (span == null || !span.markEnded()) return
        val duration = nanoTime() - span.startNanos
        val all = span.attributes + attributes
        hooks.forEach { hook -> isolated { hook.onEnd(span, duration, outcome, all) } }
    }

    override fun record(operation: Operation, durationNanos: Long, attributes: Attributes, outcome: Outcome) {
        val now = nanoTime()
        val parent = CurrentSpan.get()?.context
        val context = parent?.child() ?: SpanContext.root()
        val startEpoch = epochNanos() - durationNanos
        val span = Span(operation, attributes, context, parent, side, now - durationNanos, startEpoch)
        span.markEnded()
        hooks.forEach { hook -> isolated { hook.onEnd(span, durationNanos, outcome, attributes) } }
    }

    /** Telemetry is being switched off: the hooks drop what they queued. */
    fun disabled() = hooks.forEach { hook -> isolated { hook.onDisabled() } }

    private inline fun isolated(action: () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            // a broken hook loses this measure, nothing else
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * The telemetry of a process, switched on and off while it runs (the user changes their mind): [Telemetry.Disabled]
 * until [switchTo] gives it another one.
 */
class SwitchableTelemetry : Telemetry {
    @Volatile private var target: Telemetry = Telemetry.Disabled

    /** What measures now. */
    val current: Telemetry get() = target

    override val isEnabled: Boolean get() = target.isEnabled

    /** Measures with [telemetry] from now on; the previous one drops what it queued. */
    fun switchTo(telemetry: Telemetry) {
        val previous = target
        target = telemetry
        if (previous !== telemetry && previous is HookedTelemetry) previous.disabled()
    }

    override fun start(operation: Operation, attributes: Attributes, parent: SpanContext?): Span? =
        target.start(operation, attributes, parent)

    override fun end(span: Span?, outcome: Outcome, attributes: Attributes) = target.end(span, outcome, attributes)

    override fun record(operation: Operation, durationNanos: Long, attributes: Attributes, outcome: Outcome) =
        target.record(operation, durationNanos, attributes, outcome)
}

/** The telemetry of the IDE client: features measure with it, the app switches it with the user's consent. */
val ClientTelemetry = SwitchableTelemetry()

/** The span current on this thread, or in this coroutine (see [suspendSpan]), parent of the ones started in it. */
object CurrentSpan {
    private val onThread = ThreadLocal<Span?>()

    /** The span of this thread; null when none. */
    fun get(): Span? = onThread.get()

    /** The span of the calling coroutine, else of this thread. */
    suspend fun ofCoroutine(): Span? = coroutineContext[SpanElement]?.span ?: onThread.get()

    /** Runs [block] with [span] current on this thread. */
    inline fun <T> with(span: Span?, block: () -> T): T {
        if (span == null) return block()
        val previous = swap(span)
        try {
            return block()
        } finally {
            swap(previous)
        }
    }

    /** Makes [span] current, returning the previous one. */
    fun swap(span: Span?): Span? {
        val previous = onThread.get()
        onThread.set(span)
        return previous
    }
}

/** The span of a coroutine, parent of the spans and calls made in it. */
class SpanElement(val span: Span) : AbstractCoroutineContextElement(SpanElement) {
    /** Key of the element. */
    companion object Key : CoroutineContext.Key<SpanElement>
}

/**
 * Measures [block] as [operation]: [Outcome.Cancelled] when it is cancelled, [Outcome.Error] when it throws (the
 * exception goes on). Allocates nothing when disabled.
 */
inline fun <T> Telemetry.span(
    operation: Operation,
    attributes: Attributes = Attributes.NONE,
    block: () -> T,
): T {
    val span = start(operation, attributes) ?: return block()
    try {
        val result = CurrentSpan.with(span, block)
        end(span, Outcome.Ok)
        return result
    } catch (failure: Throwable) {
        end(span, Outcome.of(failure))
        throw failure
    }
}

/** [span] for suspending code: the span is the parent of what [block] starts, RPC calls included. */
suspend fun <T> Telemetry.suspendSpan(
    operation: Operation,
    attributes: Attributes = Attributes.NONE,
    block: suspend CoroutineScope.() -> T,
): T {
    if (!isEnabled) return coroutineScope(block)
    val parent = CurrentSpan.ofCoroutine()?.context
    val span = start(operation, attributes, parent) ?: return coroutineScope(block)
    try {
        val result = withContext(SpanElement(span)) { block() }
        end(span, Outcome.Ok)
        return result
    } catch (failure: Throwable) {
        end(span, Outcome.of(failure))
        throw failure
    }
}
