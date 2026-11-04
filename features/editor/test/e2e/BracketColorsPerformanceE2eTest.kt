package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.IdeColors
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Brackets colored by depth in a 10,001-line Kotlin file, through the real UI: opening it, an edit at its start that
 * changes the depth of every line below, and jumps across it. The depth of each line is computed for the whole file
 * off the UI thread, once on opening then once typing pauses; the lines shown color their own brackets.
 */
@OptIn(ExperimentalTestApi::class)
class BracketColorsPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val gold = IdeColors.BracketDepths[0]
    private val orchid = IdeColors.BracketDepths[1]
    private val blue = IdeColors.BracketDepths[2]
    private val red = IdeColors.BracketUnmatched

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // Line 0 opens the object; then three lines per function (line 3k+1 declares f<k>, 3k+2 returns, 3k+3 closes).
    private fun bigFile(): String {
        val functions =
            (0 until FUNCTIONS).map { i ->
                textOf(
                    "    fun f$i(x: Int): Int {",
                    "        return listOf(arrayOf(x), arrayOf($i)).size",
                    "    }",
                )
            }
        return textOf("object Big {", *functions.toTypedArray(), "}")
    }

    private fun ComposeUiTest.openBigFile(): EditorTab {
        project.file("src/Big.kt", bigFile())
        val app = launchWithProject(project)
        measure("open a 10,001-line Kotlin file until its brackets are colored", budgetMillis = 4_000) {
            openFromTree("src/Big.kt")
            awaitBracketColors(2, listOf(blue, gold, gold, gold, gold, blue))
        }
        onNodeWithTag(EditorTags.EDITOR).performClick()
        return app.project.editor.activeTab!!
    }

    @Test
    fun `an edit at the start recolors the brackets of the whole file`() =
        runComposeUiTest {
            val tab = openBigFile()
            pressInEditor(Key.MoveHome, ctrl = true)
            pressInEditor(Key.MoveEnd)
            measure(
                "delete the first opening bracket of a 10,001-line file until the lines below are recolored",
                budgetMillis = 2_000,
                budgetMegabytes = 200,
            ) {
                pressInEditor(Key.Backspace)
                awaitBracketColors(1, listOf(gold, gold, gold))
                awaitBracketColors(2, listOf(orchid, blue, blue, blue, blue, orchid))
            }
            val last = tab.document!!.lines.lastIndex
            measure("jump to the end until its unmatched bracket is red", budgetMillis = 1_500) {
                pressInEditor(Key.MoveEnd, ctrl = true)
                awaitBracketColors(last, listOf(red))
            }
        }

    @Test
    fun `jumping across a large file colors the brackets of the lines shown`() =
        runComposeUiTest {
            val tab = openBigFile()
            val scroll = tab.verticalScroll
            measure(
                "jump 10 times across the file, each time until the brackets are colored",
                budgetMillis = 4_000,
                budgetMegabytes = 150,
            ) {
                for (jump in 1..JUMPS) {
                    val row = jump * FUNCTIONS * 3 / (JUMPS + 1) / 3 * 3
                    runBlocking { scroll.scrollToRow(row) }
                    renderFrame()
                    awaitBracketColors(row + 1, listOf(orchid, orchid, orchid))
                    awaitBracketColors(row + 2, listOf(blue, gold, gold, gold, gold, blue))
                }
            }
        }

    private companion object {
        const val FUNCTIONS = 3_333
        const val JUMPS = 10
    }
}
