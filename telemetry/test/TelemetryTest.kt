package org.example.kotlineditor.telemetry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutcomeTest {
    private val telemetry = InMemoryTelemetry()

    @Test
    fun `a block that returns is ok, one that throws is an error of its class, never of its message`() {
        telemetry.span(Operation.EditorSave) { 1 }
        assertFailsWith<IllegalStateException> {
            telemetry.span(Operation.EditorSave) { error("/home/me/secret/path.php") }
        }
        val (ok, failed) = telemetry.spans
        assertEquals(Outcome.Ok, ok.outcome)
        assertEquals(Outcome.Error("IllegalStateException"), failed.outcome)
        assertTrue("secret" !in failed.toString())
    }

    @Test
    fun `a cancelled coroutine is cancelled, not an error`() =
        runBlocking<Unit> {
            val job = launch { telemetry.suspendSpan(Operation.EditorCompletion) { awaitCancellation() } }
            yield()
            job.cancelAndJoin()
            assertEquals(Outcome.Cancelled, telemetry.spans.single().outcome)
            assertEquals(Outcome.Cancelled, Outcome.of(CancellationException("newer query")))
        }

    @Test
    fun `a span ends once`() {
        val span = telemetry.start(Operation.TreeExpand)
        telemetry.end(span)
        telemetry.end(span, Outcome.Error("Late"))
        assertEquals(listOf(Outcome.Ok), telemetry.spans.map { it.outcome })
    }
}

class NestingTest {
    private val telemetry = InMemoryTelemetry()

    @Test
    fun `a span started within another is its child, of the same trace`() {
        telemetry.span(Operation.ProjectOpen) { telemetry.span(Operation.TreeExpand) {} }
        val (child, parent) = telemetry.spans
        assertEquals(parent.context.spanId, child.parent?.spanId)
        assertEquals(parent.context.traceId, child.context.traceId)
        assertNull(parent.parent)
    }

    @Test
    fun `a suspending span is the parent of what its coroutine starts, on any thread`() =
        runBlocking<Unit> {
            telemetry.suspendSpan(Operation.EditorNavigation) {
                async(kotlinx.coroutines.Dispatchers.Default) {
                    val parent = CurrentSpan.ofCoroutine()?.context
                    telemetry.end(telemetry.start(Operation.RpcClient, parent = parent))
                }.await()
            }
            val (rpc, navigation) = telemetry.spans
            assertEquals(navigation.context.spanId, rpc.parent?.spanId)
        }

    @Test
    fun `a traceparent goes through the network and back`() {
        val context = SpanContext.root()
        assertEquals(context, SpanContext.parse(context.traceparent()))
        assertEquals(55, context.traceparent().length)
        assertNull(SpanContext.parse("00-not-a-trace"))
        assertNull(SpanContext.parse("00-00000000000000000000000000000000-0000000000000000-01"))
        assertNull(SpanContext.parse(null))
    }

    @Test
    fun `a broken hook loses its measures, the feature and the other hooks go on`() {
        val kept = InMemoryHook()
        val broken =
            object : TelemetryHook {
                override fun onStart(span: Span) = error("broken")

                override fun onEnd(span: Span, durationNanos: Long, outcome: Outcome, attributes: Attributes) = error("broken")
            }
        val telemetry = HookedTelemetry(listOf(broken, kept), Side.Server)
        assertEquals(3, telemetry.span(Operation.RpcServer) { 3 })
        assertEquals(Side.Server, kept.spans.single().side)
    }

    @Test
    fun `switched off, nothing is measured and the hooks drop their queue`() {
        val switchable = SwitchableTelemetry()
        var dropped = false
        val hook =
            object : TelemetryHook {
                override fun onEnd(span: Span, durationNanos: Long, outcome: Outcome, attributes: Attributes) {}

                override fun onDisabled() {
                    dropped = true
                }
            }
        switchable.switchTo(HookedTelemetry(listOf(hook), Side.Client))
        assertTrue(switchable.isEnabled)
        switchable.switchTo(Telemetry.Disabled)
        assertTrue(dropped)
        assertNull(switchable.start(Operation.EditorSave))
    }
}

class AttributesTest {
    @Test
    fun `the attributes are a closed list, none of them a path, a name, a content, a query or a branch`() {
        val keys = Attribute.entries.map { it.key }.toSet()
        assertEquals(setOf("method", "retried", "language", "size", "location", "result"), keys)
        val forbidden = listOf("path", "file", "name", "content", "text", "query", "branch", "url", "user", "host")
        assertTrue(keys.none { key -> forbidden.any { it in key } }, "$keys")
    }

    @Test
    fun `a path, a sentence or a value out of its set never passes`() {
        val values =
            listOf(
                "/home/me/project/src/Secret.php",
                "C:\\Users\\me\\project",
                "src/main/kotlin/App.kt",
                "find this secret query",
                "feature/my-branch/with-slashes",
                "x".repeat(80),
                "",
            )
        for (value in values) assertEquals(REDACTED, Attributes.of(Attribute.Method to value)[Attribute.Method], value)
        assertEquals(REDACTED, Attributes.of(Attribute.Location to "elsewhere")[Attribute.Location])
        assertEquals(REDACTED, Attributes.of(Attribute.Size to "12345")[Attribute.Size])
        assertEquals("lsp/hover", Attributes.of(Attribute.Method to "lsp/hover")[Attribute.Method])
        assertEquals("php", Attributes.of(Attribute.Language to "php")[Attribute.Language])
        assertEquals("remote", Attributes.of(Attribute.Location to "remote")[Attribute.Location])
    }

    @Test
    fun `sizes are buckets`() {
        assertEquals("<1k", sizeBucket(0))
        assertEquals("<10k", sizeBucket(9_999))
        assertEquals("<100k", sizeBucket(10_000))
        assertEquals("<1M", sizeBucket(999_999))
        assertEquals(">=10M", sizeBucket(50_000_000))
        assertTrue(Attribute.Size.allowed!!.containsAll(listOf("<1k", "<10M", ">=10M")))
    }
}

class DisabledTest {
    @Test
    fun `disabled telemetry allocates nothing`() {
        val telemetry: Telemetry = Telemetry.Disabled
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().threadId()
        var sum = 0L
        // Warm up, so that the measure does not count the JIT's first runs.
        repeat(WARM_UP) { sum += telemetry.span(Operation.RpcClient) { it.toLong() } }
        val before = threads.getThreadAllocatedBytes(thread)
        repeat(CALLS) { sum += telemetry.span(Operation.RpcClient) { it.toLong() } }
        repeat(CALLS) { telemetry.end(telemetry.start(Operation.EditorSave)) }
        val allocated = threads.getThreadAllocatedBytes(thread) - before
        assertTrue(allocated < CALLS, "$allocated bytes for ${2 * CALLS} disabled measures ($sum)")
    }

    private companion object {
        const val WARM_UP = 200_000
        const val CALLS = 100_000
    }
}

class ConsentTest {
    @Test
    fun `KOTLIN_EDITOR_TELEMETRY=off forbids it, whatever its case, and nothing else does`() {
        for (off in listOf("off", "OFF", " false ", "0", "no")) {
            assertTrue(telemetryForbidden(mapOf(TELEMETRY_VARIABLE to off)), off)
        }
        for (on in listOf("on", "1", "", "yes")) {
            assertTrue(!telemetryForbidden(mapOf(TELEMETRY_VARIABLE to on)), on)
        }
        assertTrue(!telemetryForbidden(emptyMap()))
    }
}
