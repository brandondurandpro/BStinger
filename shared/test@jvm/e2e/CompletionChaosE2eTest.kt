package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.Tags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Code completion when the network or the server fails: edits are never lost, late answers never win. */
@OptIn(ExperimentalTestApi::class)
class CompletionChaosE2eTest {
    private val project = FakePhpServers.project()
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.openPhp(): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        openFromTree("src/index.php")
        awaitServer(app) { it.isRunning() }
        onNodeWithTag(Tags.EDITOR).performClick()
        caretToEnd()
        return app
    }

    @Test
    fun `edits typed while the network is down reach the language server`() =
        runComposeUiTest {
            val app = openPhp()
            app.network.goDown()
            awaitReconnecting()
            typeCode("function offline() {}\n")
            app.network.comeBack()
            awaitReconnected()
            typeCode("offl")
            awaitCompletion("offline")
        }

    @Test
    fun `an edit whose answer was lost is applied once`() =
        runComposeUiTest {
            val app = openPhp()
            app.network.dropFromServer = true
            typeCode("function lost() {}\n")
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            typeCode("\$after = 1;\nlo")
            awaitCompletion("lost")
            editorKeys { pressKey(Key.Escape) }
            typeCode("\n\$af")
            // The server has the text of the editor: what was typed during the drop, then after it.
            awaitCompletion("\$after")
            assertEquals(listOf("\$after"), completionLabels())
        }

    @Test
    fun `on a slow link a late answer never replaces a newer one`() =
        runComposeUiTest {
            val app = openPhp()
            app.network.latencyMs = 300
            typeCode("str")
            typeCode("t")
            awaitCompletion("strtoupper")
            // The answers for "s", "st" and "str" were cancelled or dropped: the popup keeps the newest one.
            Thread.sleep(1_500)
            waitForIdle()
            assertEquals(listOf("strtoupper"), completionLabels())
            assertEquals(
                "strt",
                app.project.editor.activeTab!!
                    .document!!
                    .lines[2],
            )
        }

    @Test
    fun `after the IDE server restarts the document is sent again`() =
        runComposeUiTest {
            val app = openPhp()
            app.process.restart()
            awaitReconnected()
            awaitServer(app) { it.isRunning() }
            typeCode("function afterRestart() {}\nafterR")
            awaitCompletion("afterRestart")
        }

    @Test
    fun `a file deleted on disk while open still completes`() =
        runComposeUiTest {
            openPhp()
            Files.delete(project.root.resolve("src/index.php"))
            typeCode("strl")
            awaitCompletion("strlen")
        }
}
