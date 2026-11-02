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
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.TerminalTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * While the terminal owns the keyboard, every IDE shortcut is input for the shell except the one which hides
 * the terminal (Alt+F12) and the devtools one (Ctrl+Alt+Shift+D): the tool windows must not toggle from there,
 * and the shell must keep working normally afterwards.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalShortcutsE2eTest {
    private val project = SampleProject.standard()

    private var appRef: E2eApp? = null

    private val ComposeUiTest.app: E2eApp get() = appRef!!

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.openTerminal() {
        onNodeWithTag(AppTags.TOOL_TERMINAL).performClick()
        awaitTag(TerminalTags.TERMINAL_INPUT)
    }

    private fun ComposeUiTest.pressMetaDigit(key: Key) {
        onNodeWithTag(TerminalTags.TERMINAL_INPUT).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(key) } }
    }

    private fun ComposeUiTest.shellIsPrompting() {
        awaitCondition {
            app.project.terminal.active
                ?.snapshot
                ?.text()
                .orEmpty()
                .any(String::isNotBlank)
        }
    }

    private fun ComposeUiTest.typeCommand(command: String) {
        onNodeWithTag(TerminalTags.TERMINAL_INPUT).performTextInput(command)
        onNodeWithTag(TerminalTags.TERMINAL_INPUT).performKeyInput { pressKey(Key.Enter) }
    }

    // The command's own echo contains it with "echo"; only its output counts.
    private fun ComposeUiTest.printed(app: E2eApp, text: String): Boolean =
        app.project.terminal.active
            ?.snapshot
            ?.text()
            .orEmpty()
            .any { "echo" !in it && it.trimEnd().endsWith(text) }

    @Test
    fun `Alt+9 and Alt+1 stay for the shell and toggle nothing`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            appRef = app
            openTerminal()
            shellIsPrompting()
            val gitBefore = app.project.toolWindows.isVisible(ToolWindow.Git)
            val projectBefore = app.project.toolWindows.isVisible(ToolWindow.Project)
            pressMetaDigit(Key.Nine)
            pressMetaDigit(Key.One)
            assertFalse(app.project.toolWindows.isVisible(ToolWindow.Git) != gitBefore, "Alt+9 must not toggle Git")
            assertFalse(
                app.project.toolWindows.isVisible(ToolWindow.Project) != projectBefore,
                "Alt+1 must not toggle the Project panel",
            )
            // The shell got Alt+9 and Alt+1 (a numeric argument for readline, ESC 9 ESC 1 for cmd.exe): Enter ends that
            // line, whatever the shell made of it, and the shell is still working: a plain command runs.
            onNodeWithTag(TerminalTags.TERMINAL_INPUT).performKeyInput { pressKey(Key.Enter) }
            typeCommand("echo still-alive")
            awaitCondition { printed(app, "still-alive") }
        }

    @Test
    fun `the devtools shortcut escapes the terminal without touching the shell`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            appRef = app
            openTerminal()
            shellIsPrompting()
            onNodeWithTag(TerminalTags.TERMINAL_INPUT).performKeyInput {
                withKeyDown(Key.CtrlLeft) {
                    withKeyDown(Key.AltLeft) {
                        withKeyDown(Key.ShiftLeft) { pressKey(Key.D) }
                    }
                }
            }
            assertTrue(app.state.devtools, "Ctrl+Alt+Shift+D opens the devtools")
            // The terminal stays shown behind the devtools: focusing its grid again must not toggle anything.
            awaitTag(TerminalTags.TERMINAL_INPUT)
            onNodeWithTag(TerminalTags.TERMINAL_INPUT).performClick()
            // Back in the terminal, the same shell runs a command with no stray input typed meanwhile.
            typeCommand("echo still-clean")
            awaitCondition { printed(app, "still-clean") }
        }

    @Test
    fun `Alt+F12 from the terminal hides it and brings it back`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            appRef = app
            openTerminal()
            shellIsPrompting()
            onNodeWithTag(
                TerminalTags.TERMINAL_INPUT,
            ).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.F12) } }
            awaitNoTag(TerminalTags.TERMINAL)
            onNodeWithTag(AppTags.TOOL_TERMINAL).performClick()
            awaitTag(TerminalTags.TERMINAL_INPUT)
            awaitCondition {
                app.project.terminal.active
                    ?.running == true
            }
        }
}
