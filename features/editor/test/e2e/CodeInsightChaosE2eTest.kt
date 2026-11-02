package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.ui.EditorTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Code intelligence when the network or the server fails: nothing stays shown that is stale, nothing is applied
 * twice, and everything works again once the link is back.
 */
@OptIn(ExperimentalTestApi::class)
class CodeInsightChaosE2eTest {
    private val project = codeInsightProject()
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun E2eApp.document(): Document = project.editor.activeTab!!.document!!

    private fun ComposeUiTest.open(path: String = "src/index.php"): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        openFromTree(path)
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    private fun ComposeUiTest.quickDocumentation() = editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Q) } }

    private fun ComposeUiTest.shown(tag: String) = onAllNodes(hasTestTag(tag), true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `documentation asked while the network is down shows once it is back`() =
        runComposeUiTest {
            val app = open()
            clickAt(2, 18)
            app.network.goDown()
            awaitReconnecting()
            quickDocumentation()
            // Nothing pretends to answer while the server is out of reach.
            assertTrue(!shown(EditorTags.HOVER))
            app.network.comeBack()
            awaitReconnected()
            awaitTag(EditorTags.HOVER_DOCUMENTATION, timeout = SERVER_START_MS)
            assertTrue("Greeter # greet" in textOf(EditorTags.HOVER_DOCUMENTATION))
            editorKeys { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.HOVER)
            quickDocumentation()
            awaitTag(EditorTags.HOVER_DOCUMENTATION, timeout = SERVER_START_MS)
        }

    @Test
    fun `a link gone silent does not leave the navigation stuck`() =
        runComposeUiTest {
            val app = open()
            clickAt(2, 18)
            app.network.blackHole = true
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
            awaitReconnecting()
            app.network.blackHole = false
            awaitReconnected()
            // Once back, Ctrl+B goes to the declaration.
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
            awaitCondition(SERVER_START_MS) {
                app.project.editor.activeTab
                    ?.relativePath == "src/Greeter.php"
            }
        }

    @Test
    fun `a quick fix whose answer was lost is applied once`() =
        runComposeUiTest {
            val app = open()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
            typeCode("missing();")
            awaitTag(EditorTags.diagnostic(4, "Error"), timeout = SERVER_START_MS, unmerged = true)
            editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
            awaitTag(EditorTags.codeAction("Create function missing"), timeout = SERVER_START_MS)
            app.network.dropFromServer = true
            editorKeys { pressKey(Key.Enter) }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            awaitCondition(SERVER_START_MS) { "function missing()" in app.document().lines }
            assertEquals(1, app.document().lines.count { it == "function missing()" })
        }

    @Test
    fun `a rename whose answer was lost renames once`() =
        runComposeUiTest {
            val app = open()
            clickAt(2, 18)
            editorKeys { withKeyDown(Key.ShiftLeft) { pressKey(Key.F6) } }
            awaitTag(EditorTags.RENAME_INPUT, timeout = SERVER_START_MS)
            onNodeWithTag(EditorTags.RENAME_INPUT).performTextReplacement("welcome")
            app.network.dropFromServer = true
            onNodeWithTag(EditorTags.RENAME_INPUT).performKeyInput { pressKey(Key.Enter) }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            awaitCondition(
                SERVER_START_MS,
            ) { "public function welcome(" in Files.readString(project.root.resolve("src/Greeter.php")) }
            assertEquals("echo \$greeter->welcome(\"world\", 1);", app.document().lines[2])
            val greeter = Files.readAllLines(project.root.resolve("src/Greeter.php"))
            val declaration = greeter.count { "function welcome(" in it }
            assertEquals(1, declaration)
        }

    @Test
    fun `on a slow link the documentation of a word left behind never shows`() =
        runComposeUiTest {
            val app = open()
            app.network.latencyMs = 400
            // The mouse rests on `Greeter`, then moves on to `greet` before the answer arrives.
            hoverAt(1, 17)
            mainClock.advanceTimeBy(600)
            hoverAt(2, 17)
            awaitTag(EditorTags.HOVER_DOCUMENTATION, timeout = SERVER_START_MS)
            awaitCondition(SERVER_START_MS) { "Greeter # greet" in textOf(EditorTags.HOVER_DOCUMENTATION) }
            assertTrue("Greets people politely." !in textOf(EditorTags.HOVER_DOCUMENTATION))
        }

    @Test
    fun `after the IDE server restarts the problems and the usage counts come back`() =
        runComposeUiTest {
            val app = open("src/Greeter.php")
            awaitTag(EditorTags.usageHint(13), timeout = SERVER_START_MS, unmerged = true)
            val before = awaitServer(app) { it.isRunning() }
            app.process.restart()
            awaitReconnected()
            // The status of the server before the restart is still shown for a moment: wait for the new process.
            awaitServer(app) { it.isRunning() && it.pid != before.pid }
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
            typeCode("missing();")
            awaitCondition(SERVER_START_MS) { textOf(EditorTags.PROBLEMS_ERRORS) == "1" }
            awaitTag(EditorTags.usageHint(13), timeout = SERVER_START_MS, unmerged = true)
            assertEquals("2 usages", textOf(EditorTags.usageHint(13)))
        }

    @Test
    fun `a file deleted on disk while open still shows its documentation`() =
        runComposeUiTest {
            open()
            Files.delete(project.root.resolve("src/index.php"))
            clickAt(2, 18)
            quickDocumentation()
            awaitTag(EditorTags.HOVER_DOCUMENTATION, timeout = SERVER_START_MS)
        }
}
