package org.example.kotlineditor.e2e

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.TerminalSnapshot
import org.example.kotlineditor.model.line
import org.example.kotlineditor.ui.IdeColors
import org.example.kotlineditor.ui.Tags
import org.example.kotlineditor.ui.TerminalBackground
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The terminal draws each row of text in one piece and the cursor, the selection and the backgrounds on the cell
 * grid: both must agree to the pixel, or the cursor drifts off its character along a long line.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalCursorE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // Columns of the image where the row's text has ink (the bars typed), as runs of consecutive columns.
    private fun inkRuns(pixels: PixelMap, top: Int, bottom: Int, left: Int, right: Int): List<IntRange> {
        val inked =
            (left until right).filter { x ->
                (top until bottom).any { y -> pixels[x, y].brightness() > INK_BRIGHTNESS }
            }
        return inked.fold(mutableListOf<IntRange>()) { runs, x ->
            val last = runs.lastOrNull()
            if (last != null && last.last == x - 1) runs[runs.size - 1] = last.first..x else runs += x..x
            runs
        }
    }

    private fun Color.brightness(): Float = (red + green + blue) / 3f

    private fun Color.near(other: Color): Boolean =
        abs(red - other.red) < COLOR_TOLERANCE &&
            abs(green - other.green) < COLOR_TOLERANCE &&
            abs(blue - other.blue) < COLOR_TOLERANCE

    private fun E2eApp.terminalSnapshot(): TerminalSnapshot? = project.terminal.active?.snapshot

    private fun ComposeUiTest.cursorOnLastBar(app: E2eApp): Boolean {
        val snapshot = app.terminalSnapshot() ?: return false
        val line = snapshot.line(snapshot.cursorRow)
        return line.getOrNull(snapshot.cursorColumn)?.text == "|" &&
            line.getOrNull(snapshot.cursorColumn + 1)?.text.isNullOrBlank()
    }

    @Test
    fun `the cursor stays on its character at the end of a long line`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
            awaitTag(Tags.TERMINAL_INPUT)
            // The prompt is drawn once the cursor has left the first column.
            awaitCondition { app.terminalSnapshot().let { it != null && it.cursorColumn > 0 } }
            val prompt = checkNotNull(app.terminalSnapshot())
            val input = onNodeWithTag(Tags.TERMINAL_INPUT)
            input.performClick()
            // Bars up to the end of the prompt's row, where a drift is the widest. Typed, never run.
            val bars = prompt.columns - prompt.cursorColumn - 2
            assertTrue(bars >= MIN_BARS, "The panel is too narrow: ${prompt.columns} columns")
            input.performTextInput("|".repeat(bars))
            input.performKeyInput { pressKey(Key.DirectionLeft) }
            awaitCondition { cursorOnLastBar(app) }
            waitForIdle()

            val pixels = input.captureToImage().toPixelMap()
            // The cursor is the accent drawn over the background: its box is the cell the terminal put it in.
            val cursorColor = IdeColors.Accent.copy(alpha = CURSOR_ALPHA).compositeOver(TerminalBackground)
            val cursor =
                (0 until pixels.width).flatMap { x ->
                    (0 until pixels.height).filter { y -> pixels[x, y].near(cursorColor) }.map { y -> x to y }
                }
            assertTrue(cursor.isNotEmpty(), "No cursor drawn")
            val left = cursor.minOf { it.first }
            val right = cursor.maxOf { it.first } + 1
            val top = cursor.minOf { it.second }
            val bottom = cursor.maxOf { it.second } + 1

            val inked = inkRuns(pixels, top, bottom, 0, pixels.width)
            val last = inked.last()
            val center = (last.first + last.last) / 2f
            assertTrue(
                center >= left && center < right,
                "The last bar is drawn at x=$center but the cursor on it covers x=$left until $right " +
                    "(bars: ${inked.takeLast(3)})",
            )
        }

    private companion object {
        // Enough for a cell width rounded to the pixel (8 for 7.8 at 13 px) to drift by more than a cell.
        const val MIN_BARS = 50
        const val CURSOR_ALPHA = 0.55f
        const val COLOR_TOLERANCE = 0.03f
        const val INK_BRIGHTNESS = 0.45f
    }
}
