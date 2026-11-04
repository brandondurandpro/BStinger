package org.example.kotlineditor.telemetry.otel

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.Aggregation
import io.opentelemetry.sdk.metrics.InstrumentSelector
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.View
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import org.example.kotlineditor.telemetry.Attributes
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.Outcome
import org.example.kotlineditor.telemetry.Span
import org.example.kotlineditor.telemetry.TelemetryHook
import java.io.Closeable
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.thread
import io.opentelemetry.api.common.Attributes as OtelAttributes
import io.opentelemetry.api.trace.Span as OtelSpan
import io.opentelemetry.api.trace.SpanContext as OtelSpanContext

/** Where and as whom the measures are exported. */
class ExportConfig(
    /** The OTLP/HTTP endpoint, without `/v1/...` (`OTEL_EXPORTER_OTLP_ENDPOINT`). */
    val endpoint: String = DEFAULT_ENDPOINT,
    /** `kotlin-editor-desktop` or `kotlin-editor-server`. */
    val serviceName: String,
    /** The version of the IDE. */
    val serviceVersion: String,
    /** A random UUID made when the user agreed: tells installations apart, says nothing about who. */
    val instanceId: String,
    /** Longest wait for the collector: a request, then the shutdown. */
    val timeoutMs: Long = TIMEOUT_MS,
    /** Spans waiting for their export; the newer ones are dropped once it is full, nothing waits. */
    val queueSize: Int = QUEUE_SIZE,
    /** How often the spans are sent. */
    val spanDelayMs: Long = SPAN_DELAY_MS,
    /** How often the histogram is sent. */
    val metricIntervalMs: Long = METRIC_INTERVAL_MS,
) {
    companion object {
        /** A collector on this machine, such as the Alloy of tools/telemetry. */
        const val DEFAULT_ENDPOINT = "http://localhost:4318"

        /** The environment variable that changes [endpoint]. */
        const val ENDPOINT_VARIABLE = "OTEL_EXPORTER_OTLP_ENDPOINT"
        private const val TIMEOUT_MS = 2_000L
        private const val QUEUE_SIZE = 2048
        private const val SPAN_DELAY_MS = 5_000L
        private const val METRIC_INTERVAL_MS = 30_000L

        /** The endpoint of [environment], else [DEFAULT_ENDPOINT]. */
        fun endpointOf(environment: Map<String, String> = System.getenv()): String =
            environment[ENDPOINT_VARIABLE]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: DEFAULT_ENDPOINT
    }
}

/** The name of the histogram; Prometheus calls it `kotlin_editor_operation_duration_seconds`. */
const val DURATION_METRIC = "kotlin_editor_operation_duration"

// Seconds: from a keystroke to a cold project open.
private val BUCKETS = listOf(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0)

private val OPERATION = AttributeKey.stringKey("operation")
private val OUTCOME = AttributeKey.stringKey("outcome")
private val SIDE = AttributeKey.stringKey("side")
private val ERROR_TYPE = AttributeKey.stringKey("error.type")
private const val NANOS_PER_SECOND = 1e9

// The SDK logs each failed export as SEVERE; a collector out of reach is expected, not an error of the IDE. Kept in a
// field: java.util.logging holds its loggers weakly, and a collected one would forget its level.
private val SDK_LOGGER: Logger = Logger.getLogger("io.opentelemetry")

/**
 * Exports the spans to OpenTelemetry: each one as a trace span (to Tempo) and in the duration histogram (to Mimir),
 * over OTLP/HTTP. The SDK is configured by hand, its threads are daemons, a collector out of reach costs a dropped
 * batch: nothing waits for it, and [close] waits at most [ExportConfig.timeoutMs].
 */
class OtelHook(private val config: ExportConfig) :
    TelemetryHook,
    Closeable {
    private val ids = PresetIds()
    private val gate = GatedExporter(spanExporter())
    private val tracerProvider =
        SdkTracerProvider
            .builder()
            .setResource(resource())
            .setIdGenerator(ids)
            .setSampler(Sampler.alwaysOn())
            .addSpanProcessor(
                BatchSpanProcessor
                    .builder(gate)
                    .setMaxQueueSize(config.queueSize)
                    .setMaxExportBatchSize(minOf(BATCH_SIZE, config.queueSize))
                    .setScheduleDelay(Duration.ofMillis(config.spanDelayMs))
                    .setExporterTimeout(Duration.ofMillis(config.timeoutMs))
                    .build(),
            ).build()
    private val meterProvider =
        SdkMeterProvider
            .builder()
            .setResource(resource())
            .registerView(
                InstrumentSelector.builder().setName(DURATION_METRIC).build(),
                View.builder().setAggregation(Aggregation.explicitBucketHistogram(BUCKETS)).build(),
            ).registerMetricReader(
                PeriodicMetricReader
                    .builder(
                        OtlpHttpMetricExporter
                            .builder()
                            .setEndpoint("${config.endpoint}/v1/metrics")
                            .setTimeout(Duration.ofMillis(config.timeoutMs))
                            .build(),
                    ).setInterval(Duration.ofMillis(config.metricIntervalMs))
                    .build(),
            ).build()
    private val tracer = tracerProvider.get("kotlin-editor")
    private val histogram =
        meterProvider
            .get("kotlin-editor")
            .histogramBuilder(DURATION_METRIC)
            .setUnit("s")
            .setDescription("How long the operations of the IDE take")
            .build()

    init {
        SDK_LOGGER.level = Level.OFF
    }

    override fun onEnd(span: Span, durationNanos: Long, outcome: Outcome, attributes: Attributes) {
        histogram.record(durationNanos / NANOS_PER_SECOND, labelsOf(span, outcome))
        export(span, durationNanos, outcome, attributes)
    }

    // The trace span, with the ids of [span]: the parent across processes is the one its traceparent named.
    private fun export(span: Span, durationNanos: Long, outcome: Outcome, attributes: Attributes) {
        val context = span.context
        val parent = span.parent
        val parentContext =
            if (parent == null) {
                Context.root()
            } else {
                val remote =
                    OtelSpanContext.createFromRemoteParent(
                        parent.traceId,
                        parent.spanIdHex,
                        TraceFlags.getSampled(),
                        TraceState.getDefault(),
                    )
                Context.root().with(OtelSpan.wrap(remote))
            }
        ids.preset(context.traceId, context.spanIdHex)
        try {
            val builder =
                tracer
                    .spanBuilder(span.operation.id)
                    .setParent(parentContext)
                    .setSpanKind(kindOf(span.operation))
                    .setStartTimestamp(span.startEpochNanos, TimeUnit.NANOSECONDS)
                    .setAttribute(OPERATION, span.operation.id)
                    .setAttribute(SIDE, span.side.label)
                    .setAttribute(OUTCOME, outcome.label)
            attributes.forEach { attribute, value -> builder.setAttribute(attribute.key, value) }
            val started = builder.startSpan()
            if (outcome is Outcome.Error) {
                started.setAttribute(ERROR_TYPE, outcome.type)
                started.setStatus(StatusCode.ERROR)
            }
            started.end(span.startEpochNanos + durationNanos, TimeUnit.NANOSECONDS)
        } finally {
            ids.clear()
        }
    }

    // Switched off: what is queued is dropped rather than sent.
    override fun onDisabled() {
        gate.open = false
        close()
    }

    /** Sends what is queued (unless switched off) and stops, waiting at most the timeout. */
    override fun close() {
        val deadline = System.nanoTime() + config.timeoutMs * NANOS_PER_MILLI
        // The meter provider's shutdown sends the last histogram before it even returns its result, waiting for the
        // collector for seconds: it runs on a daemon thread, side by side with the spans' shutdown.
        val metrics = thread(isDaemon = true, name = "otel-metrics-shutdown") { meterProvider.shutdown() }
        // The spans' shutdown may block before it returns too (a full queue sent to a silent collector): the same.
        val spans =
            thread(isDaemon = true, name = "otel-spans-shutdown") {
                tracerProvider.shutdown().join(config.timeoutMs, TimeUnit.MILLISECONDS)
            }
        spans.join(remainingMs(deadline))
        metrics.join(remainingMs(deadline))
    }

    // What is left of the wait until [deadline], at least 1 ms: Thread.join(0) would wait forever.
    private fun remainingMs(deadline: Long): Long {
        val left = (deadline - System.nanoTime()) / NANOS_PER_MILLI
        return left.coerceAtLeast(1L)
    }

    private fun spanExporter(): SpanExporter =
        OtlpHttpSpanExporter
            .builder()
            .setEndpoint("${config.endpoint}/v1/traces")
            .setTimeout(Duration.ofMillis(config.timeoutMs))
            .setConnectTimeout(Duration.ofMillis(config.timeoutMs))
            .build()

    private fun resource(): Resource =
        Resource.create(
            OtelAttributes
                .builder()
                .put("service.name", config.serviceName)
                .put("service.version", config.serviceVersion)
                .put("service.instance.id", config.instanceId)
                .put("os.type", osType())
                .build(),
        )

    private fun labelsOf(span: Span, outcome: Outcome): OtelAttributes =
        OtelAttributes.of(OPERATION, span.operation.id, OUTCOME, outcome.label, SIDE, span.side.label)

    private fun kindOf(operation: Operation): SpanKind =
        when (operation) {
            Operation.RpcClient -> SpanKind.CLIENT
            Operation.RpcServer -> SpanKind.SERVER
            else -> SpanKind.INTERNAL
        }

    private companion object {
        const val BATCH_SIZE = 512
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** The OS family, as OpenTelemetry names it (`linux`, `windows`, `darwin`). */
internal fun osType(name: String = System.getProperty("os.name").orEmpty()): String {
    val lower = name.lowercase()
    return when {
        "win" in lower -> "windows"
        "mac" in lower || "darwin" in lower -> "darwin"
        "linux" in lower -> "linux"
        else -> "other"
    }
}

/** Passes the spans on while [open]; drops them once telemetry was switched off. */
private class GatedExporter(private val exporter: SpanExporter) : SpanExporter {
    @Volatile var open = true

    override fun export(spans: Collection<SpanData>): CompletableResultCode =
        if (open) exporter.export(spans) else CompletableResultCode.ofSuccess()

    override fun flush(): CompletableResultCode = if (open) exporter.flush() else CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = exporter.shutdown()
}
