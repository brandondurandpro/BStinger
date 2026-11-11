package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

// Files of the project, each holding the searched word: as many Find in Files results.
private const val FILES = 2_000

// Lines of the large file opened.
private const val LINES = 20_000

// The work below takes about 300 ms and allocates 135 MB here, and measuring it adds nothing measurable: the budget
// of the gap leaves room for a slow runner, not for a cost per line, per file or per result.
private const val GAP_BUDGET_MS = 1_000L
private const val GAP_BUDGET_MB = 64L
private const val RUN_BUDGET_MS = 30_000L
private const val RUNS = 3

/**
 * The cost of measuring: the same work through the UI (a 20,000-line file opened, Find in Files over 2,000 files, the
 * files listed for Go to File) with telemetry off, then on. The gap must stay small, on both sides of the protocol.
 */
@OptIn(ExperimentalTestApi::class)
class TelemetryPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            (1..FILES).forEach { file("src/pkg${it % 40}/File$it.kt", "val needle$it = $it\n") }
            file("Large.kt", (1..LINES).joinToString("\n", postfix = "\n") { "val line$it = $it" })
        }

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.work() {
        openFromTree("Large.kt")
        awaitEditorReady()
        onNodeWithTag(EditorTags.EDITOR).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
        }
        awaitTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("needle")
        awaitCondition {
            runCatching { onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("files", substring = true) }
                .isSuccess
        }
        onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performKeyInput { pressKey(Key.Escape) }
        awaitNoTag(TreeTags.FIND_IN_FILES)
        onNodeWithTag(EditorTags.tabClose("Large.kt")).performClick()
        awaitNoTag(EditorTags.tabClose("Large.kt"))
        renderFrame()
    }

    // The cheapest of a few runs, time and memory apart. The memory is what every thread allocates, and the same work
    // allocates 59 MB or about 140 MB from one run to the next, telemetry on or off (what runs in the background
    // when the tab closes): one run of each made the gap 0 MB or 75 to 85 MB on the Windows runner.
    private fun ComposeUiTest.cheapest(label: String): Cost {
        val runs = (1..RUNS).map { costOf { work() } }
        val cost = Cost(runs.minOf { it.nanos }, runs.minOf { it.bytes })
        return report(label, cost, RUN_BUDGET_MS)
    }

    @Test
    fun `measuring a large project's work costs little next to the work`() =
        runComposeUiTest {
            val capture = TelemetryCapture()
            val app = launchWithProject(project, preferences = TelemetryCapture.refused(), telemetry = capture)
            // Warms the JIT and the server's caches, so that both runs below find them warm.
            work()
            val off = cheapest("Large project, telemetry off")
            runOnUiThread {
                app.state.settings.telemetry
                    .choose(true)
            }
            awaitCondition { app.server.telemetry.telemetry.isEnabled }
            val on = cheapest("Large project, telemetry on")
            report("Telemetry gap (on - off)", on - off, GAP_BUDGET_MS, GAP_BUDGET_MB)
            awaitCondition { capture.clientSpansOf(Operation.SearchFindInFiles).isNotEmpty() }
            assertTrue(capture.clientSpansOf(Operation.EditorOpenFile).isNotEmpty())
            assertTrue(capture.serverSpans.any { it.operation == Operation.RpcServer })
        }
}
