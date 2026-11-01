package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
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
import org.example.kotlineditor.syntax.treesitter.GrammarSpec
import org.example.kotlineditor.ui.IdeColors
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tree-sitter highlighting of a 10,002-line Kotlin file, through the real UI: opening it, typing at its end and at
 * its start (every line after the edit moves), jumping across it and scrolling it. The grammar is downloaded before
 * measuring: these tests measure parsing and coloring, not the network. Memory counts the allocations of every
 * thread: each edit is parsed again off the UI thread, reading the lines that changed rather than a copy of the whole
 * text (about 1 MB per keystroke here, all included, against 4 MB with a copy).
 */
@OptIn(ExperimentalTestApi::class)
class SyntaxHighlightingPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // Three lines per class: line 3k declares C<k>, line 3k+1 reads `name.length`, line 3k+2 closes it.
    private fun bigFile(): String =
        (0 until CLASSES).joinToString("\n") { i ->
            textOf(
                "class C$i(val name: String) {",
                "    fun f$i(x: Int): Int = x * $i + name.length // comment $i",
                "}",
            )
        }

    private fun ComposeUiTest.openBigFile(): EditorTab {
        project.file("src/Big.kt", bigFile())
        val kotlin = GrammarSpec.all.single { it.id == "kotlin" }
        assertNotNull(runBlocking { TestGrammars.shared.await(kotlin) })
        val app = launchWithProject(project)
        measure("open a 10,002-line Kotlin file until colored", budgetMillis = 4_000, budgetMegabytes = 300) {
            openFromTree("src/Big.kt")
            awaitColor(1, "length", IdeColors.Property)
        }
        val tab = app.project.editor.activeTab!!
        onNodeWithTag(Tags.EDITOR).performClick()
        return tab
    }

    @Test
    fun `typing at the end and at the start of a large file`() =
        runComposeUiTest {
            val tab = openBigFile()
            pressInEditor(Key.MoveEnd, ctrl = true)
            pressInEditor(Key.Enter)
            measure(
                "type 20 characters at the end of a 10,002-line file",
                budgetMillis = 3_000,
                budgetMegabytes = 100,
            ) {
                typeInEditor("val z = name.length ")
            }
            val last = tab.document!!.lines.lastIndex
            measure("color the line typed at the end", budgetMillis = 1_500) {
                awaitColor(last, "length", IdeColors.Property)
            }
            pressInEditor(Key.MoveHome, ctrl = true)
            measure(
                "type 20 lines at the start of a 10,002-line file",
                budgetMillis = 15_000,
                budgetMegabytes = 1_200,
            ) {
                repeat(TYPED_LINES) { typeInEditor("val a = b.size\n") }
            }
            measure("color the lines typed at the start", budgetMillis = 1_500) {
                awaitColor(0, "size", IdeColors.Property)
                awaitColor(TYPED_LINES + 1, "length", IdeColors.Property)
            }
            assertWindowBounded(tab)
        }

    @Test
    fun `jumping across a large file colors the lines shown`() =
        runComposeUiTest {
            val tab = openBigFile()
            val scroll = tab.verticalScroll
            measure(
                "jump 10 times across the file, each time until colored",
                budgetMillis = 4_000,
                budgetMegabytes = 150,
            ) {
                for (jump in 1..JUMPS) {
                    val row = jump * CLASSES * 3 / (JUMPS + 1) / 3 * 3
                    runBlocking { scroll.scrollToRow(row) }
                    renderFrame()
                    awaitColor(row + 1, "length", IdeColors.Property)
                }
            }
            assertWindowBounded(tab)
        }

    @Test
    fun `scrolling a large colored file`() =
        runComposeUiTest {
            val tab = openBigFile()
            val scroll = tab.verticalScroll

            // A scroll in progress (wheel, drag): the lines' semantics wait for its end, the canvas draws every frame.
            fun scrollFrames(moving: Boolean) =
                costOf {
                    runBlocking { scroll.scrollToRow(0) }
                    renderFrame()
                    val held = CoroutineScope(Dispatchers.Unconfined)
                    held.launch { scroll.scrollableState.scroll { awaitCancellation() } }
                    repeat(SCROLL_FRAMES) { frame ->
                        if (moving) runBlocking { scroll.scrollToRow(frame * ROWS_PER_FRAME) }
                        renderFrame()
                    }
                    held.cancel()
                }
            scrollFrames(moving = true)
            val still = scrollFrames(moving = false)
            report(
                "scroll 200 frames of 10 rows (net of still frames)",
                scrollFrames(moving = true) - still,
                budgetMillis = 3_000,
                budgetMegabytes = 400,
            )
            val end = SCROLL_FRAMES * ROWS_PER_FRAME // 2000 closes a class: 2002 reads `name.length`.
            runBlocking { scroll.scrollToRow(end) }
            renderFrame()
            awaitColor(end + 2, "length", IdeColors.Property)
            assertWindowBounded(tab)
        }

    @Test
    fun `showing a hidden large file again parses it with its colors kept meanwhile`() =
        runComposeUiTest {
            val tab = openBigFile()
            val syntax = tab.document!!.syntax!!
            openFromTree("src/main/kotlin/App.kt")
            awaitCondition { !syntax.isParsed }
            measure(
                "show the hidden 10,002-line file again until parsed",
                budgetMillis = 2_000,
                budgetMegabytes = 60,
            ) {
                onNodeWithTag(Tags.tab("src/Big.kt")).performClick()
                renderFrame()
                assertEquals(IdeColors.Property, colorOf(1, "length"), "the last colors stay while it is parsed")
                awaitCondition { syntax.isParsed }
            }
            onNodeWithTag(Tags.EDITOR).performClick()
            pressInEditor(Key.MoveHome, ctrl = true)
            measure("type a line at the start once parsed again", budgetMillis = 1_500, budgetMegabytes = 100) {
                typeInEditor("val a = b.size\n")
                awaitColor(0, "size", IdeColors.Property)
            }
        }

    // Only the lines around the visible ones are colored, never the whole file.
    private fun ComposeUiTest.assertWindowBounded(tab: EditorTab) {
        val window = assertNotNull(tab.document!!.syntax!!.highlights).window
        val visible = (tab.verticalScroll.viewportHeightPx / 10).toInt()
        assertTrue(window.count() <= maxOf(MAX_WINDOW, visible), "colored lines: $window")
    }

    private companion object {
        const val CLASSES = 3_334
        const val TYPED_LINES = 20
        const val JUMPS = 10
        const val SCROLL_FRAMES = 200
        const val ROWS_PER_FRAME = 10
        const val MAX_WINDOW = 600
    }
}
