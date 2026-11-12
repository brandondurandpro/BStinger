package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val LINES = 9_500
private const val ROUNDS = 3
private const val ROWS_PER_STEP = 40
private const val PATH = "big/Report.kt"

/**
 * The memory of the IDE while a large file is scrolled up and down, through the real UI. Each line laid out holds
 * kilobytes of native memory. Laid out by Compose, it was freed when its Java objects were collected: kept a moment
 * by the cache of lines, they grew old and the young collections of a scroll did not free them, so the native
 * memory in use grew by hundreds of megabytes a round, without end. The editor now closes the lines it drops.
 */
@OptIn(ExperimentalTestApi::class)
class EditorScrollMemoryPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // Lines that all differ, as long as those of real code, with strings, calls and comments to color.
    private fun largeFile(): String =
        (0 until LINES).joinToString("\n") { n ->
            "    val value$n = compute(first$n, \"text number $n\", listOf($n, ${n * 2})) + other.call($n) // line $n"
        }

    @Test
    fun `scrolling a large file up and down does not keep growing the native memory`() =
        runComposeUiTest {
            // Only glibc tells what is in use.
            val usedBefore = NativeMemory.inUseMegabytes()
            if (usedBefore != null) scrollAndMeasure(usedBefore)
        }

    private fun ComposeUiTest.scrollAndMeasure(usedBefore: Long) {
        project.file(PATH, largeFile())
        val app = launchWithProject(project)
        openFromTree(PATH)
        awaitTag(EditorTags.line(1))
        val tab = checkNotNull(app.project.editor.activeTab)
        awaitCondition { tab.document?.syntax?.isParsed == true }
        val growth =
            List(ROUNDS) { round ->
                measure("scroll a $LINES-line file down and up, round $round", 30_000) { upAndDown(tab) }
                checkNotNull(NativeMemory.inUseMegabytes()) - usedBefore
            }
        val label = "native memory in use after each round".padEnd(LABEL_WIDTH)
        println("PERF $label $growth MB (budget $GROWTH_BUDGET_MB MB)")
        assertTrue(growth.all { it <= GROWTH_BUDGET_MB }, "native memory in use grew by $growth MB")
    }

    private fun ComposeUiTest.upAndDown(tab: EditorTab) {
        val rows = checkNotNull(tab.document).lineCount
        val down = 0 until rows step ROWS_PER_STEP
        for (row in down + down.reversed()) {
            runBlocking { tab.verticalScroll.scrollToRow(row) }
            renderFrame()
        }
    }

    private companion object {
        const val LABEL_WIDTH = 50

        // Measured: 181, 200 and 185 MB after three rounds; 700 to 900 MB and growing when Compose laid the lines out.
        const val GROWTH_BUDGET_MB = 400L
    }
}
