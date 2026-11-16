package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.model.TerminalKey
import org.example.kotlineditor.model.TerminalMouseAction
import org.example.kotlineditor.model.TerminalMouseButton
import org.example.kotlineditor.model.text
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.TerminalTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val Left = TerminalMouseButton.Left

// Rows the wheel moves per scroll event, as TerminalInput sends them (3 rows up or down).
private const val WHEEL_ROWS = 3

// More lines than any test panel can show, so the scrollback has somewhere to go.
private const val MANY_LINES = 60

// DECSET/DECRST modes of the alternate screen (a full-screen program), and of a program that reads the mouse
// (button-event tracking, then SGR reports).
private val ALTERNATE_SCREEN = listOf("1049")
private val FULL_SCREEN_WITH_MOUSE = listOf("1049", "1002", "1006")

/** The wheel of the terminal grid: scrolling the scrollback, and moving a full-screen program. */
@OptIn(ExperimentalTestApi::class)
class TerminalScrollE2eTest {
    private val project = SampleProject.standard()

    private val windows = System.getProperty("os.name").startsWith("Windows")

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.openTerminal(): E2eApp {
        val app = launchWithProject(project)
        onNodeWithTag(AppTags.TOOL_TERMINAL).performClick()
        awaitTag(TerminalTags.TERMINAL_INPUT)
        onNodeWithTag(TerminalTags.TERMINAL_INPUT).performClick()
        return app
    }

    private fun ComposeUiTest.runCommand(command: String) {
        // The terminal is already focused (openTerminal clicked it): a click here would be reported to a
        // program that tracks the mouse, instead of just focusing the grid.
        onNodeWithTag(TerminalTags.TERMINAL_INPUT).performTextInput(command)
        onNodeWithTag(TerminalTags.TERMINAL_INPUT).performKeyInput { pressKey(Key.Enter) }
    }

    private fun ComposeUiTest.wheel(vertical: Float) {
        onNodeWithTag(TerminalTags.TERMINAL_INPUT).performMouseInput {
            moveTo(center)
            scroll(vertical, ScrollWheel.Vertical)
        }
    }

    // Interrupts the line the shell was reading: whatever sat in its input (the reports it does not read) is
    // dropped, and a fresh prompt comes back.
    private fun ComposeUiTest.flushShellInput() {
        onNodeWithTag(TerminalTags.TERMINAL_INPUT).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.C) }
        }
    }

    private fun E2eApp.screenLines(): List<String> =
        project.terminal.active
            ?.snapshot
            ?.text()
            ?.map { it.trimEnd() }
            .orEmpty()

    // Whether the last command printed [text]: the lines echoing the command itself are skipped.
    private fun E2eApp.printed(text: String): Boolean =
        screenLines().any { "echo" !in it && "for" !in it && it.endsWith(text) }

    private fun E2eApp.scrollOffset(): Long =
        project.terminal.active
            ?.snapshot
            ?.scrollOffset
            ?: 0L

    private fun E2eApp.alternateScreen(): Boolean =
        project.terminal.active
            ?.snapshot
            ?.alternateScreen
            ?: false

    private fun E2eApp.mouseTracking(): Boolean =
        project.terminal.active
            ?.snapshot
            ?.mouseTracking
            ?: false

    // Prints escape sequences, as a full-screen program would: the bytes travel as the output of the command,
    // so the emulator sees them whatever the shell echoes. Every switch carries its own ESC.
    private fun ComposeUiTest.printSequences(enter: Boolean, modes: List<String>) {
        val onOff = if (enter) "h" else "l"
        runCommand(
            if (windows) {
                val expression = modes.joinToString(" + ") { "(([char]27) + '[?$it$onOff')" }
                "powershell -NoProfile -Command \"Write-Host -NoNewline $expression\""
            } else {
                val sequence = modes.joinToString("") { "\\033[?$it$onOff" }
                "printf '$sequence'"
            },
        )
    }

    // Prints more lines than the panel can show, so that the scrollback holds rows to scroll back to.
    private fun ComposeUiTest.printManyLines() {
        runCommand(
            if (windows) {
                "for /l %i in (1,1,$MANY_LINES) do @echo fill-%i"
            } else {
                "for a in 1 2 3 4 5; do for b in 1 2 3 4 5 6 7 8 9 10 11 12; do echo fill-\$a-\$b; done; done"
            },
        )
    }

    @Test
    fun `the wheel scrolls the screen back to earlier output and back`() =
        runComposeUiTest {
            val app = openTerminal()
            printManyLines()
            awaitCondition { app.printed("fill-5-12") }
            // The wheel goes up into the scrollback: earlier rows are shown, the last ones are gone.
            wheel(-100f)
            awaitCondition { app.scrollOffset() > 0 }
            assertTrue(app.screenLines().none { it.endsWith("fill-5-12") }, app.screenLines().joinToString("\n"))
            // And back down, to the live output at the bottom.
            wheel(100f)
            awaitCondition { app.scrollOffset() == 0L }
        }

    @Test
    fun `the wheel moves a full-screen program with the cursor keys`() =
        runComposeUiTest {
            val app = openTerminal()
            // History to scroll back to once the full-screen program is gone (the alternate screen has none).
            printManyLines()
            awaitCondition { app.printed("fill-5-12") }
            printSequences(enter = true, ALTERNATE_SCREEN)
            awaitCondition { app.alternateScreen() }
            // A full-screen program owns the screen and there is no scrollback: the wheel sends the cursor
            // keys the program reads, three rows per scroll event, not a scroll of the viewport.
            app.terminalInput.clear()
            wheel(-100f)
            awaitCondition { app.terminalInput.keys.isNotEmpty() }
            assertArrows(app, TerminalKey.ArrowUp)
            app.terminalInput.clear()
            wheel(100f)
            awaitCondition { app.terminalInput.keys.isNotEmpty() }
            assertArrows(app, TerminalKey.ArrowDown)
            // Out of the alternate screen, the wheel scrolls the scrollback again and sends no key.
            printSequences(enter = false, ALTERNATE_SCREEN)
            awaitCondition { !app.alternateScreen() }
            app.terminalInput.clear()
            wheel(-100f)
            awaitCondition { app.scrollOffset() > 0 && app.terminalInput.keys.isEmpty() }
        }

    @Test
    fun `the wheel is reported to a program that enabled mouse tracking`() =
        runComposeUiTest {
            val app = openTerminal()
            // History to scroll back to once the program is gone (the alternate screen has none).
            printManyLines()
            awaitCondition { app.printed("fill-5-12") }
            // A full-screen program that reads the mouse (`opencode`, `htop`, `tmux`…) switches to the
            // alternate screen and enables mouse tracking: the wheel must become a mouse report, not cursor keys.
            printSequences(enter = true, FULL_SCREEN_WITH_MOUSE)
            awaitCondition { app.alternateScreen() && app.mouseTracking() }
            app.terminalInput.clear()
            wheel(-100f)
            awaitCondition { app.terminalInput.mouseEvents.isNotEmpty() }
            assertWheelReports(app, TerminalMouseButton.WheelUp)
            app.terminalInput.clear()
            wheel(100f)
            awaitCondition { app.terminalInput.mouseEvents.isNotEmpty() }
            assertWheelReports(app, TerminalMouseButton.WheelDown)
            // A click on the grid is reported to the program as a press and a release of the left button.
            app.terminalInput.clear()
            onNodeWithTag(TerminalTags.TERMINAL_INPUT).performMouseInput {
                moveTo(center)
                press()
                release()
            }
            awaitCondition {
                val events = app.terminalInput.mouseEvents
                val pressed = events.any { it.action == TerminalMouseAction.Press && it.button == Left }
                val released = events.any { it.action == TerminalMouseAction.Release && it.button == Left }
                pressed && released
            }
            assertTrue(app.terminalInput.keys.isEmpty(), app.terminalInput.keys.joinToString("\n"))
            // Out of the program, the wheel scrolls the scrollback again and reports nothing. The mouse reports
            // the shell received sit in its input (it is not reading them): Ctrl+C drops that line first.
            flushShellInput()
            printSequences(enter = false, FULL_SCREEN_WITH_MOUSE)
            awaitCondition { !app.alternateScreen() && !app.mouseTracking() }
            app.terminalInput.clear()
            wheel(-100f)
            awaitCondition {
                val scrolled = app.scrollOffset() > 0
                val silent = app.terminalInput.mouseEvents.isEmpty() && app.terminalInput.keys.isEmpty()
                scrolled && silent
            }
        }

    // Every event of the wheel sends [WHEEL_ROWS] presses; several events may arrive for one scroll.
    private fun assertArrows(app: E2eApp, key: TerminalKey) {
        val keys = app.terminalInput.keys
        assertTrue(keys.isNotEmpty() && keys.all { it.key == key && it.text.isEmpty() }, keys.joinToString("\n"))
        assertEquals(0, keys.size % WHEEL_ROWS, keys.joinToString("\n"))
    }

    // One wheel report per event, at a cell of the grid, and never cursor keys instead.
    private fun assertWheelReports(app: E2eApp, button: TerminalMouseButton) {
        val events = app.terminalInput.mouseEvents
        val snapshot =
            app.project.terminal.active
                ?.snapshot
        val columns = snapshot?.columns ?: 0
        val rows = snapshot?.rows ?: 0
        val inside = events.all { it.column in 0 until columns && it.row in 0 until rows }
        val reports = events.all { it.button == button && it.action == TerminalMouseAction.Press }
        assertTrue(events.isNotEmpty() && reports, events.joinToString("\n"))
        assertTrue(inside, events.joinToString("\n"))
        assertTrue(app.terminalInput.keys.isEmpty(), app.terminalInput.keys.joinToString("\n"))
    }
}
