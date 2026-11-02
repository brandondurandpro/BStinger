package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val RETURN_LINE = 15
private const val NAME_COLUMN = 38

private const val RESTART_ATTEMPTS = 12
private const val ATTEMPT_MS = 2_000L

// The editor's own first step on `$name`: the word, without its dollar.
private val WORD_WITHOUT_DOLLAR = span(RETURN_LINE, 38, RETURN_LINE, 42)

/** Ctrl+W in PHP when the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class PhpSelectionChaosE2eTest {
    private val project = codeInsightProject()
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        openFromTree("src/Greeter.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    // From the caret on `$name`, the server's first range.
    private fun ComposeUiTest.firstServerStep(app: E2eApp) {
        clickAt(RETURN_LINE, NAME_COLUMN)
        extendSelection()
        awaitCondition(SERVER_START_MS) { app.selection() == NAME_STEPS.first() }
    }

    // A restarted server has the documents again only after a while: until then Ctrl+W uses the editor's own steps,
    // and the `$` of `$name` says the server answered.
    private fun ComposeUiTest.awaitServerSelection(app: E2eApp) {
        var served = false
        var attempts = 0
        while (!served && attempts < RESTART_ATTEMPTS) {
            attempts++
            clickAt(RETURN_LINE, NAME_COLUMN)
            extendSelection()
            served = runCatching { awaitCondition(ATTEMPT_MS) { app.selection() == NAME_STEPS.first() } }.isSuccess
        }
        assertTrue(served, "Ctrl+W never used the restarted server's ranges")
    }

    @Test
    fun `on a slow link three quick Ctrl+W all count, once each`() =
        runComposeUiTest {
            val app = open()
            app.network.latencyMs = 400
            clickAt(RETURN_LINE, NAME_COLUMN)
            editorKeys {
                withKeyDown(Key.CtrlLeft) {
                    pressKey(Key.W)
                    pressKey(Key.W)
                    pressKey(Key.W)
                }
            }
            awaitCondition(SERVER_START_MS) { app.selection() == NAME_STEPS[2] }
            // Nothing more arrives late.
            waitForIdle()
            awaitCondition { app.selection() == NAME_STEPS[2] }
            extendSelection()
            awaitCondition { app.selection() == NAME_STEPS[3] }
        }

    @Test
    fun `while the network is down Ctrl+W still selects, with the editor's own steps, then the server's once back`() =
        runComposeUiTest {
            val app = open()
            app.network.goDown()
            awaitReconnecting()
            clickAt(RETURN_LINE, NAME_COLUMN)
            extendSelection()
            // Not stuck waiting for the server: the word, as the editor finds it.
            awaitCondition(SERVER_START_MS) { app.selection() == WORD_WITHOUT_DOLLAR }
            app.network.comeBack()
            awaitReconnected()
            firstServerStep(app)
        }

    @Test
    fun `when the link goes silent Ctrl+W still selects, and the server's ranges come back with it`() =
        runComposeUiTest {
            val app = open()
            app.network.blackHole = true
            clickAt(RETURN_LINE, NAME_COLUMN)
            extendSelection()
            awaitCondition(SERVER_START_MS) { app.selection() == WORD_WITHOUT_DOLLAR }
            awaitReconnecting()
            app.network.blackHole = false
            awaitReconnected()
            firstServerStep(app)
        }

    @Test
    fun `after the server restarts Ctrl+W grows through the language server's ranges again`() =
        runComposeUiTest {
            val app = open()
            firstServerStep(app)
            app.process.restart()
            awaitReconnected()
            awaitServer(app) { it.isRunning() }
            awaitServerSelection(app)
            // Elsewhere, so that the server is asked again: `greet`, its line, then the method from its modifiers.
            clickAt(13, 22)
            listOf(span(13, 20, 13, 25), span(13, 4, 13, 59), NAME_STEPS[2]).forEach { step ->
                extendSelection()
                awaitCondition(SERVER_START_MS) { app.selection() == step }
            }
        }
}
