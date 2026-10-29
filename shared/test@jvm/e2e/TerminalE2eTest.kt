package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
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

    private fun androidx.compose.ui.test.ComposeUiTest.run(command: String) {
        onNodeWithTag(Tags.TERMINAL_INPUT).performTextInput(command)
        onNodeWithTag(Tags.TERMINAL_INPUT).performKeyInput { pressKey(Key.Enter) }
    }

    @Test
    fun `Alt+F12 opens a terminal that runs commands in the project directory`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TREE).performClick()
            onNodeWithTag(Tags.TREE).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.F12) } }
            awaitTag(Tags.TERMINAL_INPUT)
            run("echo hello-terminal")
            awaitCondition { "hello-terminal" in app.project.terminal.lines }
            run("ls")
            awaitCondition { "README.md" in app.project.terminal.lines }
            assertTrue(
                app.project.terminal.lines
                    .contains("$ echo hello-terminal"),
            )
        }

    @Test
    fun `Up arrow recalls previous commands`() =
        runComposeUiTest {
            launchWithProject(project)
            onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
            awaitTag(Tags.TERMINAL_INPUT)
            run("echo one")
            run("echo two")
            onNodeWithTag(Tags.TERMINAL_INPUT).performKeyInput { pressKey(Key.DirectionUp) }
            onNodeWithTag(Tags.TERMINAL_INPUT).assertTextEquals("echo two")
            onNodeWithTag(Tags.TERMINAL_INPUT).performKeyInput { pressKey(Key.DirectionUp) }
            onNodeWithTag(Tags.TERMINAL_INPUT).assertTextEquals("echo one")
        }

    @Test
    fun `the shell exit is reported and closing the project stops the shell`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
            awaitTag(Tags.TERMINAL_INPUT)
            awaitCondition { app.project.terminal.running }
            run("exit")
            awaitCondition { "[Process finished]" in app.project.terminal.lines }
            assertFalse(app.project.terminal.running)
            // A new command restarts a shell.
            run("echo again")
            awaitCondition { "again" in app.project.terminal.lines }
            val terminal = app.project.terminal
            runOnUiThread { app.state.closeProject() }
            awaitTag(Tags.WELCOME)
            awaitCondition { !terminal.running }
        }

    @Test
    fun `the terminal can be hidden and output cleared`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
            awaitTag(Tags.TERMINAL_INPUT)
            run("echo x")
            awaitCondition { "x" in app.project.terminal.lines }
            onNodeWithTag(Tags.TERMINAL_CLEAR).performClick()
            awaitCondition {
                app.project.terminal.lines
                    .isEmpty()
            }
            onNodeWithTag(Tags.TERMINAL_HIDE).performClick()
            awaitNoTag(Tags.TERMINAL)
        }
}
