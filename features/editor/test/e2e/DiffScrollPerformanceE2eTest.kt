package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.GitTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val FUNCTIONS = 2_500
private const val LINES_PER_FUNCTION = 8
private const val STEPS = 200
private const val ROWS_PER_STEP = 7
private const val MAX_COMPOSED_ROWS = 120
private const val COLOR_TIMEOUT_MS = 12_000L
private const val SCROLL_BUDGET_MS = 12_000L
private const val SCROLL_BUDGET_MB = 400L
private const val LABEL_WIDTH = 50
private const val PATH = "big/Large.kt"

/**
 * Performance of the diff of a large file, through the real UI: a new file of 20,000 lines of Kotlin is one hunk
 * of as many added lines, which the grammar colors at once, then scrolled.
 *
 * Its native memory is what made the IDE grow to gigabytes: a `malloc` for each capture of the grammar (see
 * `HighlightMemoryTest`), then the paragraphs of the rows scrolled through, about 300 KB a frame, which Skia
 * frees when their Java objects are collected. They must be garbage, not a leak: once collected, little is left.
 */
@OptIn(ExperimentalTestApi::class)
class DiffScrollPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun largeFile(): String =
        (0 until FUNCTIONS).joinToString("\n") { n ->
            listOf(
                "/** The function number $n. */",
                "fun compute$n(first: Int, second: String = \"none\"): List<Int> {",
                "    val total = first + second.length * $n // a comment",
                "    if (total > 10 && second.isNotEmpty()) return listOf(total, first, $n)",
                "    val text = \"value \$total of \${second.trim()}\"",
                "    return text.map { it.code }.filter { it != 32 }",
                "}",
                "",
            ).joinToString("\n")
        }

    @Test
    fun `the diff of a large file is colored, then scrolled without leaking native memory`() =
        runComposeUiTest {
            project.commitAll()
            project.file(PATH, largeFile())
            val app = launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            val change = awaitTag(GitTags.gitChange(PATH))
            val usedBefore = NativeMemory.inUseMegabytes()
            measure("open and color the diff of a ${FUNCTIONS * LINES_PER_FUNCTION}-line file", budgetMillis = 12_000) {
                change.performClick()
                awaitTag(EditorTags.DIFF_SYNTAX, timeout = COLOR_TIMEOUT_MS)
                renderFrame()
            }
            val tab = checkNotNull(app.project.editor.activeTab)

            val jumps = scroll(tab, moving = true, userScroll = false) - scroll(tab, moving = false, userScroll = false)
            report("scroll the diff, $STEPS jumps of $ROWS_PER_STEP rows", jumps, SCROLL_BUDGET_MS, SCROLL_BUDGET_MB)
            val wheel = scroll(tab, moving = true, userScroll = true) - scroll(tab, moving = false, userScroll = true)
            report("scroll the diff, $STEPS steps of a scroll in progress", wheel, SCROLL_BUDGET_MS, SCROLL_BUDGET_MB)

            // The list is virtualized: the rows on screen, not the 20,000 of the diff.
            val composed = tab.listState.layoutInfo.visibleItemsInfo.size
            assertTrue(composed <= MAX_COMPOSED_ROWS, "$composed rows composed")

            if (usedBefore != null) {
                // What the frames allocated is garbage: collected, then freed by the cleaners of Skia.
                System.gc()
                awaitCondition { usedSince(usedBefore) <= LEFT_BUDGET_MB }
                val left = usedSince(usedBefore)
                val label = "native memory left in use by the diff".padEnd(LABEL_WIDTH)
                println("PERF $label $left MB (budget $LEFT_BUDGET_MB MB)")
            }
        }

    private fun usedSince(before: Long): Long = checkNotNull(NativeMemory.inUseMegabytes()) - before

    // The cost of [STEPS] rendered frames, the list moved between them when [moving]; [userScroll] keeps a scroll
    // in progress meanwhile, as a wheel or a drag does.
    private fun ComposeUiTest.scroll(tab: EditorTab, moving: Boolean, userScroll: Boolean): Cost {
        val list = tab.listState
        runBlocking { list.scrollToItem(0) }
        renderFrame()
        val held = CoroutineScope(Dispatchers.Unconfined)
        if (userScroll) held.launch { list.scroll { awaitCancellation() } }
        val cost =
            costOf {
                repeat(STEPS) { step ->
                    if (moving) runBlocking { list.scrollToItem(step * ROWS_PER_STEP) }
                    renderFrame()
                }
            }
        held.cancel()
        return cost
    }

    private companion object {
        // Measured: 180 MB (the tree of the file, the caches of Skia); 300 MB more while the frames are not collected.
        const val LEFT_BUDGET_MB = 350L
    }
}
