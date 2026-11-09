package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.telemetry.Attribute
import org.example.kotlineditor.telemetry.FinishedSpan
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.Outcome
import org.example.kotlineditor.telemetry.otel.ExportConfig
import org.example.kotlineditor.telemetry.otel.OtelHook
import org.example.kotlineditor.ui.EditorTags
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Files opened while the collector is out of reach (as many as the tree shows): each one measured and exported.
private const val FILES = 8

// Opening a file stays this fast whatever the collector does: the export never runs on the UI.
private const val OPEN_BUDGET_MS = 3_000L

// Closing the export waits at most its timeout (2 s), plus some room for a slow runner.
private const val CLOSE_BUDGET_MS = 2_500L
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * What the user sees when the measures cannot leave, or when the link with the server breaks while they are being
 * taken: the IDE works as without telemetry, closes on time, and measures each request once.
 */
@OptIn(ExperimentalTestApi::class)
class TelemetryChaosE2eTest {
    private val project =
        SampleProject.standard().apply { (1..FILES).forEach { file("many/File$it.kt", "val x$it = $it\n") } }
    private val collector = FakeCollector()

    @AfterTest
    fun cleanup() {
        collector.close()
        project.delete()
    }

    private fun hookTo(endpoint: String) =
        OtelHook(
            ExportConfig(
                endpoint = endpoint,
                serviceName = "kotlin-editor-desktop",
                serviceVersion = "test",
                instanceId = TelemetryCapture.INSTANCE_ID,
                spanDelayMs = 50,
                metricIntervalMs = 100,
            ),
        )

    // Opens every file of many/, each within its budget, and returns once the last one shows.
    private fun ComposeUiTest.openMany() {
        for (index in 1..FILES) {
            measure("Open a file, collector out of reach", OPEN_BUDGET_MS) {
                openFromTree("many/File$index.kt")
                awaitEditorReady()
            }
        }
    }

    private fun closesOnTime(hook: OtelHook) {
        val started = System.nanoTime()
        hook.close()
        val closed = (System.nanoTime() - started) / NANOS_PER_MILLI
        assertTrue(closed < CLOSE_BUDGET_MS, "closing took $closed ms")
    }

    @Test
    fun `a collector that never answers leaves the IDE as fast, and closing it waits at most its timeout`() =
        runComposeUiTest {
            collector.blackHole = true
            val hook = hookTo(collector.endpoint)
            val capture = TelemetryCapture(listOf(hook))
            launchWithProject(project, preferences = TelemetryCapture.agreed(), telemetry = capture)
            openMany()
            assertTrue(capture.clientSpansOf(Operation.EditorOpenFile).size >= FILES)
            closesOnTime(hook)
        }

    @Test
    fun `a collector out of reach loses the measures quietly`() =
        runComposeUiTest {
            val closedPort = ServerSocket(0).use { it.localPort }
            val hook = hookTo("http://127.0.0.1:$closedPort")
            val capture = TelemetryCapture(listOf(hook))
            launchWithProject(project, preferences = TelemetryCapture.agreed(), telemetry = capture)
            openMany()
            closesOnTime(hook)
        }

    @Test
    fun `the collector back, the measures reach it with the installation's resource`() =
        runComposeUiTest {
            val hook = hookTo(collector.endpoint)
            val capture = TelemetryCapture(listOf(hook))
            launchWithProject(project, preferences = TelemetryCapture.agreed(), telemetry = capture)
            openFromTree("README.md")
            awaitEditorReady()
            awaitCondition { collector.traces.any { "editor.open_file" in it } }
            val trace = collector.traces.first { "editor.open_file" in it }
            assertTrue(TelemetryCapture.INSTANCE_ID in trace)
            assertTrue(project.path !in trace && "README" !in trace)
            closesOnTime(hook)
        }

    @Test
    fun `a save whose answer was lost is measured once on the server, and as retried by the UI`() =
        runComposeUiTest {
            val capture = TelemetryCapture()
            val app =
                launchWithProject(
                    project,
                    preferences = TelemetryCapture.agreed(),
                    chaos = true,
                    telemetry = capture,
                )
            openFromTree("README.md")
            awaitEditorReady()
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { pressKey(Key.Z) }
            capture.clear()
            app.network.dropFromServer = true
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
            awaitCondition { "z" in project.read("README.md") }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            val writes = { spans: List<FinishedSpan> -> spans.filter { it.attributes[Attribute.Method] == "fs/write" } }
            // The span of the write is finished once the retry answered: wait for it, not for its first attempt.
            awaitCondition { writes(capture.clientSpans).any { it.attributes[Attribute.Retried] == "true" } }
            val sent = writes(capture.clientSpans).single()
            assertEquals("true", sent.attributes[Attribute.Retried])
            assertEquals(Outcome.Ok, sent.outcome)
            assertEquals(1, writes(capture.serverSpans).size)
            assertEquals(sent.context.spanId, writes(capture.serverSpans).single().parent?.spanId)
            awaitCondition { capture.clientSpansOf(Operation.Reconnect).isNotEmpty() }
        }

    @Test
    fun `a restarted server forgets the consent, and is told again when the UI reconnects`() =
        runComposeUiTest {
            val capture = TelemetryCapture()
            val app =
                launchWithProject(
                    project,
                    preferences = TelemetryCapture.agreed(),
                    chaos = true,
                    telemetry = capture,
                )
            awaitCondition { app.server.telemetry.telemetry.isEnabled }
            app.process.restart()
            awaitReconnected()
            awaitCondition { app.server.telemetry.telemetry.isEnabled }
            capture.clear()
            openFromTree("docs/guide.md")
            awaitEditorReady()
            awaitCondition { capture.serverSpans.any { it.attributes[Attribute.Method] == "fs/read" } }
            assertEquals(Outcome.Ok, capture.clientSpansOf(Operation.EditorOpenFile).lastOrNull()?.outcome)
        }
}
