package org.example.kotlineditor.telemetry.otel

import org.example.kotlineditor.e2e.FakeCollector
import org.example.kotlineditor.telemetry.Attribute
import org.example.kotlineditor.telemetry.Attributes
import org.example.kotlineditor.telemetry.HookedTelemetry
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.Side
import org.example.kotlineditor.telemetry.SpanContext
import org.example.kotlineditor.telemetry.span
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtelHookTest {
    private val collector = FakeCollector()

    @AfterTest
    fun cleanup() = collector.close()

    private fun config(
        endpoint: String = collector.endpoint,
        queueSize: Int = 2048,
        metricIntervalMs: Long = 100,
    ) =
        ExportConfig(
            endpoint = endpoint,
            serviceName = "kotlin-editor-desktop",
            serviceVersion = "1.2.3",
            instanceId = "0b8a3f4e-instance",
            queueSize = queueSize,
            spanDelayMs = 50,
            metricIntervalMs = metricIntervalMs,
        )

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Nothing received: ${collector.requests.map { it.path }}" }
            Thread.sleep(POLL_MS)
        }
    }

    @Test
    fun `spans go to the traces with their resource, and their durations to the histogram`() {
        val hook = OtelHook(config())
        val telemetry = HookedTelemetry(listOf(hook), Side.Client)
        telemetry.span(Operation.EditorOpenFile, Attributes.of(Attribute.Language to "php")) {}
        // Wait for the request that carries the span, not just for any request: on a loaded runner the first one
        // received may be another export.
        awaitUntil {
            val tracesWithSpan = collector.traces.filter { "editor.open_file" in it }
            val metricsWithDuration = collector.metrics.filter { DURATION_METRIC in it }
            tracesWithSpan.isNotEmpty() && metricsWithDuration.isNotEmpty()
        }
        val trace = checkNotNull(collector.traces.firstOrNull { "editor.open_file" in it })
        for (text in listOf("editor.open_file", "kotlin-editor-desktop", "1.2.3", "0b8a3f4e-instance", "php", "client")) {
            assertTrue(text in trace, text)
        }
        assertTrue(collector.metrics.any { "editor.open_file" in it && "ok" in it })
        hook.close()
    }

    @Test
    fun `a span keeps the ids its traceparent gave the server`() {
        val hook = OtelHook(config())
        val telemetry = HookedTelemetry(listOf(hook), Side.Server)
        val remote = SpanContext.root()
        telemetry.end(telemetry.start(Operation.RpcServer, parent = remote))
        hook.close()
        awaitUntil { collector.traces.isNotEmpty() }
        // Protobuf stores the ids as raw bytes.
        val trace = collector.traces.first().body
        assertTrue(trace.containsBytes(bytesOf(remote.traceId)), "trace id")
        assertTrue(trace.containsBytes(bytesOf(remote.spanIdHex)), "parent span id")
    }

    @Test
    fun `switched off, what is queued is dropped, not sent`() {
        val hook = OtelHook(config().let { ExportConfig(it.endpoint, it.serviceName, it.serviceVersion, it.instanceId, spanDelayMs = 60_000) })
        val telemetry = HookedTelemetry(listOf(hook), Side.Client)
        repeat(10) { telemetry.span(Operation.EditorSave) {} }
        telemetry.disabled()
        Thread.sleep(POLL_MS * 5)
        assertEquals(emptyList(), collector.traces.map { it.path })
    }

    @Test
    fun `a collector out of reach costs nothing to wait for, a full queue drops, closing takes at most the timeout`() {
        val (endpoint, silent) = FakeCollector.silentEndpoint()
        silent.use {
            val hook = OtelHook(config(endpoint, queueSize = 256))
            val telemetry = HookedTelemetry(listOf(hook), Side.Client)
            val runtime = Runtime.getRuntime()
            System.gc()
            val before = runtime.totalMemory() - runtime.freeMemory()
            val started = System.nanoTime()
            repeat(SPANS) { telemetry.span(Operation.RpcClient, Attributes.of(Attribute.Method to "fs/read")) {} }
            val measured = (System.nanoTime() - started) / NANOS_PER_MILLI
            System.gc()
            val grown = (runtime.totalMemory() - runtime.freeMemory() - before) / MB
            assertTrue(measured < MEASURE_BUDGET_MS, "$SPANS spans measured in $measured ms")
            assertTrue(grown < MEMORY_BUDGET_MB, "$grown MB kept for $SPANS spans with a silent collector")
            val closing = System.nanoTime()
            hook.close()
            val closed = (System.nanoTime() - closing) / NANOS_PER_MILLI
            assertTrue(closed < CLOSE_BUDGET_MS, "closed in $closed ms")
        }
    }

    @Test
    fun `closing with a histogram still to send to a silent collector takes at most the timeout`() {
        val (endpoint, silent) = FakeCollector.silentEndpoint()
        silent.use {
            // The histogram is never sent before the close, whose last export then waits for the silent collector.
            val hook = OtelHook(config(endpoint, metricIntervalMs = NEVER_MS))
            val telemetry = HookedTelemetry(listOf(hook), Side.Client)
            telemetry.span(Operation.GitStatus) {}
            val closing = System.nanoTime()
            hook.close()
            val closed = (System.nanoTime() - closing) / NANOS_PER_MILLI
            assertTrue(closed < CLOSE_BUDGET_MS, "closed in $closed ms")
        }
    }

    @Test
    fun `an unreachable port loses the batches quietly`() {
        val port = ServerSocket(0).use { it.localPort }
        val hook = OtelHook(config("http://127.0.0.1:$port"))
        val telemetry = HookedTelemetry(listOf(hook), Side.Client)
        repeat(100) { telemetry.span(Operation.GitStatus) {} }
        val closing = System.nanoTime()
        hook.close()
        assertTrue((System.nanoTime() - closing) / NANOS_PER_MILLI < CLOSE_BUDGET_MS)
    }

    @Test
    fun `the endpoint comes from the environment, the default is this machine`() {
        assertEquals("http://localhost:4318", ExportConfig.endpointOf(emptyMap()))
        assertEquals("https://otel.example.com", ExportConfig.endpointOf(mapOf(ExportConfig.ENDPOINT_VARIABLE to "https://otel.example.com/")))
        assertEquals("linux", osType("Linux"))
        assertEquals("windows", osType("Windows 11"))
        assertEquals("darwin", osType("Mac OS X"))
    }

    private fun bytesOf(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.containsBytes(part: ByteArray): Boolean =
        (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

    private companion object {
        const val WAIT_MS = 30_000L
        const val POLL_MS = 20L
        const val SPANS = 100_000
        const val MB = 1024 * 1024
        const val NANOS_PER_MILLI = 1_000_000L
        const val MEASURE_BUDGET_MS = 10_000L
        const val MEMORY_BUDGET_MB = 64
        const val CLOSE_BUDGET_MS = 2_500L
        const val NEVER_MS = 600_000L
    }
}
