package org.example.kotlineditor.telemetry.otel

import io.opentelemetry.sdk.trace.IdGenerator

/**
 * Gives the SDK the ids the telemetry already chose for a span (they went into the traceparent of its RPCs), set
 * just before the span is built on the same thread; random ones otherwise.
 */
internal class PresetIds : IdGenerator {
    private val traceId = ThreadLocal<String?>()
    private val spanId = ThreadLocal<String?>()

    fun preset(trace: String, span: String) {
        traceId.set(trace)
        spanId.set(span)
    }

    fun clear() {
        traceId.remove()
        spanId.remove()
    }

    override fun generateSpanId(): String = spanId.get() ?: IdGenerator.random().generateSpanId()

    override fun generateTraceId(): String = traceId.get() ?: IdGenerator.random().generateTraceId()
}
