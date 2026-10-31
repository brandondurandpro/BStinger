package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.state.EditorRenderer
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.EditorViewport
import org.example.kotlineditor.state.LazyListViewport
import org.example.kotlineditor.state.VerticalScroll
import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.Test

private const val LINES = 9_500
private const val STEPS = 300
private const val ROWS_PER_STEP = 3
private const val ROUNDS = 3
private const val BLAME_BLOCK = 17
private const val AUTHORS = 5
private const val MONTHS = 12
private const val BYTES_PER_MB = 1_048_576
private const val NANOS_PER_MILLI = 1_000_000

/**
 * Scrolls a 9,500-line file with blame annotations through both renderers and prints the memory allocated and the
 * time taken by the scroll (each step is rendered; the cost of rendering an unchanged frame is subtracted). The
 * canvas is measured twice: jumping from row to row, and as during a wheel or drag scroll, when its semantics
 * nodes wait for the end of the scroll. Skipped unless run with `-Deditor.benchmark=true`, e.g.
 * `JAVA_TOOL_OPTIONS=-Deditor.benchmark=true ./kotlin test -m shared --include-classes='*EditorScrollBenchmark'`.
 */
@OptIn(ExperimentalTestApi::class)
class EditorScrollBenchmark {
    private val project = SampleProject.standard()

    private val allocated: Long
        get() = (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).totalThreadAllocatedBytes

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private enum class Case(val renderer: EditorRenderer, val label: String) {
        Rows(EditorRenderer.Rows, "composed rows"),
        CanvasJumps(EditorRenderer.Canvas, "canvas, jumps"),
        CanvasScroll(EditorRenderer.Canvas, "canvas, user scroll"),
    }

    @Test
    fun `scrolling with the canvas and with the composed rows`() {
        if (System.getProperty("editor.benchmark") != "true") return
        runComposeUiTest {
            val text = (0 until LINES).joinToString("\n") { "    val value$it = listOf($it, \"t$it\") // $it" }
            project.file("Big.kt", text)
            val app = launchWithProject(project)
            openFromTree("Big.kt")
            val tab = app.project.editor.activeTab!!
            awaitCondition { tab.document != null }
            runOnIdle {
                tab.blame = (0 until LINES).map { BlameLine(it, "c${it / BLAME_BLOCK}", author(it), date(it)) }
                tab.blameVisible = true
            }
            val results = linkedMapOf<Case, Measure>()
            for (case in Case.entries) {
                runOnIdle { if (app.state.editorRenderer != case.renderer) app.state.toggleEditorRenderer() }
                waitForIdle()
                val canvas = case.renderer == EditorRenderer.Canvas
                val viewport = if (canvas) tab.verticalScroll else LazyListViewport(tab.listState)
                val userScroll = case == Case.CanvasScroll
                scroll(tab, viewport, moving = true, userScroll) // warm-up
                val rounds =
                    List(ROUNDS) {
                        val baseline = scroll(tab, viewport, moving = false, userScroll)
                        scroll(tab, viewport, moving = true, userScroll) - baseline
                    }
                results[case] = Measure(median(rounds.map { it.bytes }), median(rounds.map { it.nanos }))
            }
            println("BENCHMARK $LINES lines, $STEPS steps of $ROWS_PER_STEP rows, blame on (median of $ROUNDS)")
            for ((case, result) in results) {
                println(
                    "BENCHMARK ${case.label.padEnd(20)} ${result.bytes / BYTES_PER_MB} MB allocated, " +
                        "${result.nanos / NANOS_PER_MILLI} ms",
                )
            }
        }
    }

    // Bytes allocated and nanoseconds spent, each step rendered; [userScroll] keeps a scroll in progress meanwhile.
    private fun ComposeUiTest.scroll(
        tab: EditorTab,
        viewport: EditorViewport,
        moving: Boolean,
        userScroll: Boolean,
    ): Measure {
        runBlocking { viewport.scrollToRow(0) }
        waitForIdle()
        val held = CoroutineScope(Dispatchers.Unconfined)
        if (userScroll) held.launch { (viewport as VerticalScroll).scrollableState.scroll { awaitCancellation() } }
        val bytes = allocated
        val start = System.nanoTime()
        repeat(STEPS) { step ->
            if (moving) runBlocking { viewport.scrollToRow(step * ROWS_PER_STEP) }
            waitForIdle()
            onRoot().captureToImage()
        }
        val result = Measure(allocated - bytes, System.nanoTime() - start)
        held.cancel()
        check(tab.document != null)
        return result
    }

    private class Measure(val bytes: Long, val nanos: Long) {
        operator fun minus(other: Measure) = Measure(bytes - other.bytes, nanos - other.nanos)
    }

    private fun median(values: List<Long>) = values.sorted()[values.size / 2]

    private fun author(line: Int) = "Author${line / BLAME_BLOCK % AUTHORS} Lastname"

    private fun date(line: Int) = "2026-%02d-15".format(line / BLAME_BLOCK % MONTHS + 1)
}
