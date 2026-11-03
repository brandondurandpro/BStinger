package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.InlayHintsMode
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test

// `echo $greeter->greet("world", 1);` of src/index.php: where its arguments start.
private const val LINE = 2
private const val FIRST = 21
private const val SECOND = 30

/** Inlay hints drawn in the code when the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class PhpInlayHintsChaosE2eTest {
    private val project = codeInsightProject()
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun E2eApp.document() = project.editor.activeTab!!.document!!

    // The hints chosen inline in an earlier session.
    private fun ComposeUiTest.open(): E2eApp {
        val preferences = InMemoryPreferenceStore(mapOf(PreferenceKeys.INLAY_HINTS to InlayHintsMode.Inline.name))
        val app = launchWithProject(project, preferences, chaos = true, languageServers = servers.settings)
        openFromTree("src/index.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.inlayHint(LINE, SECOND), unmerged = true, timeout = SERVER_START_MS)
        return app
    }

    @Test
    fun `text typed while the network is down moves the hints with it, and they are asked again after`() =
        runComposeUiTest {
            val app = open()
            app.network.goDown()
            awaitReconnecting()
            // Typed in "world" (the hint is 6 cells before it): the next argument's hint follows at once.
            clickAt(LINE, FIRST + "name: ".length + 2)
            typeInEditor("ab")
            awaitTag(EditorTags.inlayHint(LINE, SECOND + 2), unmerged = true)
            awaitNoTag(EditorTags.inlayHint(LINE, SECOND), unmerged = true)
            app.network.comeBack()
            awaitReconnected()
            awaitTag(EditorTags.inlayHint(LINE, FIRST), unmerged = true, timeout = SERVER_START_MS)
            onNodeWithTag(EditorTags.inlayHint(LINE, SECOND + 2), useUnmergedTree = true).assertTextEquals("times: ")
        }

    @Test
    fun `an argument deleted while the link is silent moves the next hint left, the server confirms it after`() =
        runComposeUiTest {
            val app = open()
            app.network.blackHole = true
            // Caret before `"world"` (just after its hint), then Delete removes its 7 characters.
            clickAt(LINE, FIRST + "name: ".length)
            editorKeys { repeat(WORLD.length) { pressKey(Key.Delete) } }
            awaitTag(EditorTags.inlayHint(LINE, SECOND - WORLD.length), unmerged = true)
            awaitNoTag(EditorTags.inlayHint(LINE, SECOND), unmerged = true)
            // The IDE notices the silent link, then reconnects once it carries data again.
            awaitReconnecting()
            app.network.blackHole = false
            awaitReconnected()
            // `greet(, 1)`: for the server the only argument left is the first parameter.
            val moved = EditorTags.inlayHint(LINE, SECOND - WORLD.length)
            awaitCondition(SERVER_START_MS) { textOf(moved) == "name: " }
        }

    @Test
    fun `a slow link shows the hints of the latest text, never older ones`() =
        runComposeUiTest {
            val app = open()
            app.network.latencyMs = SLOW_MS
            clickAt(LINE, FIRST + "name: ".length + 2)
            typeInEditor("a")
            typeInEditor("b")
            awaitTag(EditorTags.inlayHint(LINE, SECOND + 2), unmerged = true)
            // The answers for the text with "a" only arrive after: they must not put the hint back one column left.
            awaitCondition(SERVER_START_MS) { "ab" in app.document().lines[LINE] }
            repeat(SETTLE_CHECKS) {
                awaitNoTag(EditorTags.inlayHint(LINE, SECOND + 1), unmerged = true)
                waitForIdle()
            }
            awaitTag(EditorTags.inlayHint(LINE, SECOND + 2), unmerged = true)
        }

    @Test
    fun `after the server restarts the hints come back`() =
        runComposeUiTest {
            val app = open()
            app.process.restart()
            awaitReconnected()
            awaitServer(app) { it.isRunning() }
            // An edit asks for them again from the new server.
            clickAt(LINE, 0)
            typeInEditor(" ")
            awaitTag(EditorTags.inlayHint(LINE, SECOND + 1), unmerged = true, timeout = SERVER_START_MS)
            onNodeWithTag(EditorTags.inlayHint(LINE, FIRST + 1), useUnmergedTree = true).assertTextEquals("name: ")
        }
}

private const val SLOW_MS = 400L
private const val WORLD = "\"world\""
private const val SETTLE_CHECKS = 5
