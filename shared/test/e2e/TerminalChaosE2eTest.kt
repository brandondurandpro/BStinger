package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.text
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Shells running on the server while the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class TerminalChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun screenLines(app: E2eApp): List<String> =
        app.project.terminal.active
            ?.snapshot
            ?.text()
            ?.map { it.trimEnd() }
            .orEmpty()

    // Lines a command printed: the output can land after a prompt; lines echoing the command itself are skipped.
    private fun printedLines(app: E2eApp, text: String): Int =
        screenLines(app).count { line -> "echo" !in line && line.endsWith(text) }

    private fun ComposeUiTest.openTerminal(app: E2eApp) {
        onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
        awaitTag(Tags.TERMINAL_INPUT)
        awaitCondition { screenLines(app).any { it.isNotBlank() } }
    }

    private fun ComposeUiTest.runCommand(command: String) {
        onNodeWithTag(Tags.TERMINAL_INPUT).performClick()
        onNodeWithTag(Tags.TERMINAL_INPUT).performTextInput(command)
        onNodeWithTag(Tags.TERMINAL_INPUT).performKeyInput { pressKey(Key.Enter) }
    }

    @Test
    fun `a command typed while the network is down runs once it is back, and only once`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openTerminal(app)
            app.network.goDown()
            awaitReconnecting()
            runCommand("echo chaos-once")
            app.network.comeBack()
            awaitCondition { printedLines(app, "chaos-once") > 0 }
            awaitReconnected()
            assertEquals(1, printedLines(app, "chaos-once"))
        }

    @Test
    fun `the output of a command survives a connection cut`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openTerminal(app)
            runCommand("echo before-cut")
            awaitCondition { printedLines(app, "before-cut") > 0 }
            app.network.cutAll()
            runCommand("echo after-cut")
            awaitCondition { printedLines(app, "after-cut") > 0 }
            assertEquals(1, printedLines(app, "before-cut"))
            assertEquals(1, printedLines(app, "after-cut"))
        }

    @Test
    fun `a shell lost in a server restart says so, and a new tab works`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openTerminal(app)
            app.process.restart()
            awaitReconnected()
            // The old screen must not pretend the shell still runs: typing there would go nowhere.
            awaitTag(Tags.TERMINAL_LOST).assertIsDisplayed()
            onNodeWithTag(Tags.TERMINAL_NEW).performClick()
            awaitCondition { app.project.terminal.tabs.size == 2 && screenLines(app).any { it.isNotBlank() } }
            awaitNoTag(Tags.TERMINAL_LOST)
            runCommand("echo new-shell")
            awaitCondition { printedLines(app, "new-shell") > 0 }
        }
}
