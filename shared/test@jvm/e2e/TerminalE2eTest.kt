package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.model.text
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TerminalE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.screenLines(app: E2eApp): List<String> =
        app.project.terminal.active
            ?.snapshot
            ?.text()
            ?.map { it.trimEnd() }
            .orEmpty()

    // Whether a command printed [text]. The test types before the shell shows its prompt, so the output can land
    // after one (`$ text`, `C:\project>text`); lines echoing the command itself are skipped.
    private fun ComposeUiTest.printed(app: E2eApp, text: String): Boolean =
        screenLines(app).any { "echo" !in it && it.endsWith(text) }

    private fun ComposeUiTest.runCommand(command: String) {
        onNodeWithTag(Tags.TERMINAL_INPUT).performClick()
        onNodeWithTag(Tags.TERMINAL_INPUT).performTextInput(command)
        onNodeWithTag(Tags.TERMINAL_INPUT).performKeyInput { pressKey(Key.Enter) }
    }

    private fun ComposeUiTest.typeKeys(keys: List<Key>) {
        onNodeWithTag(Tags.TERMINAL_INPUT).performClick()
        onNodeWithTag(Tags.TERMINAL_INPUT).performKeyInput { keys.forEach { pressKey(it) } }
    }

    @Test
    fun `Alt+F12 opens a terminal that runs commands in the project directory`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TREE).performClick()
            onNodeWithTag(Tags.TREE).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.F12) } }
            awaitTag(Tags.TERMINAL_INPUT)
            runCommand("echo hello-terminal")
            awaitCondition { printed(app, "hello-terminal") }
            // Checked right away: ConPTY repaints the screen and may push earlier lines out of it. Joined without
            // spaces because after a long prompt (cmd.exe shows the whole path) the command wraps.
            val screen = screenLines(app)
            assertTrue(
                "echohello-terminal" in screen.joinToString("").filterNot(Char::isWhitespace),
                screen.joinToString("\n"),
            )
            runCommand(if (System.getProperty("os.name").startsWith("Windows")) "dir /b" else "ls")
            awaitCondition { screenLines(app).any { "README.md" in it } }
        }

    @Test
    fun `typed keys are encoded by the server engine`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
            awaitTag(Tags.TERMINAL_INPUT)
            typeKeys(
                listOf(
                    Key.E,
                    Key.C,
                    Key.H,
                    Key.O,
                    Key.Spacebar,
                    Key.K,
                    Key.E,
                    Key.Y,
                    Key.Minus,
                    Key.P,
                    Key.A,
                    Key.T,
                    Key.H,
                    Key.Enter,
                ),
            )
            awaitCondition { printed(app, "key-path") }
        }

    @Test
    fun `each tab runs its own shell`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
            awaitTag(Tags.TERMINAL_INPUT)
            runCommand("echo first-tab")
            awaitCondition { printed(app, "first-tab") }
            onNodeWithTag(Tags.TERMINAL_NEW).performClick()
            awaitCondition { app.project.terminal.tabs.size == 2 }
            runCommand("echo second-tab")
            awaitCondition { printed(app, "second-tab") }
            onNodeWithTag(Tags.terminalTab(1)).performClick()
            awaitCondition { printed(app, "first-tab") }
            onNodeWithTag(Tags.TERMINAL_CLOSE).performClick()
            awaitCondition { app.project.terminal.tabs.size == 1 }
        }

    @Test
    fun `the shell exit is reported and closing the project stops the shell`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
            awaitTag(Tags.TERMINAL_INPUT)
            awaitCondition {
                app.project.terminal.active
                    ?.running == true
            }
            runCommand("exit")
            awaitCondition {
                app.project.terminal.active
                    ?.running == false
            }
            val terminalTab = app.project.terminal.active
            assertFalse(terminalTab?.running ?: true)
            val terminal = app.project.terminal
            runOnUiThread { app.state.closeProject() }
            awaitTag(Tags.WELCOME)
            awaitCondition { terminal.tabs.isEmpty() }
        }

    @Test
    fun `the terminal can be hidden and cleared`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
            awaitTag(Tags.TERMINAL_INPUT)
            runCommand("echo marker-x")
            awaitCondition { printed(app, "marker-x") }
            onNodeWithTag(Tags.TERMINAL_CLEAR).performClick()
            awaitCondition { screenLines(app).none { "marker-x" in it } }
            onNodeWithTag(Tags.TERMINAL_HIDE).performClick()
            awaitNoTag(Tags.TERMINAL)
        }
}
