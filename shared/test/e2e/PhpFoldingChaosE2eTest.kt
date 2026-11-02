package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test

/** PHP code folding when the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class PhpFoldingChaosE2eTest {
    private val project = codeInsightProject().apply { file("src/Imports.php", IMPORTS_PHP) }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        openFromTree("src/Imports.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.foldMarker(FIRST_USE), unmerged = true, timeout = SERVER_START_MS)
        return app
    }

    @Test
    fun `a line inserted while the network is down drops the stale markers, back in place with the network`() =
        runComposeUiTest {
            val app = open()
            app.network.goDown()
            awaitReconnecting()
            clickAt(2, 0)
            editorKeys { pressKey(Key.Enter) }
            // The regions were for the text before: no marker points at the wrong lines.
            awaitNoTag(EditorTags.foldMarker(FIRST_USE), unmerged = true)
            app.network.comeBack()
            awaitReconnected()
            awaitTag(EditorTags.foldMarker(FIRST_USE + 1), unmerged = true, timeout = SERVER_START_MS).performClick()
            awaitNoTag(EditorTags.line(LAST_USE + 1))
        }

    @Test
    fun `a region folded before the network goes down stays folded, and unfolds while it is down`() =
        runComposeUiTest {
            val app = open()
            awaitTag(EditorTags.foldMarker(FUNCTION_LINE), unmerged = true).performClick()
            awaitNoTag(EditorTags.line(FUNCTION_LINE + 1))
            app.network.goDown()
            awaitReconnecting()
            awaitTag(EditorTags.foldMarker(FUNCTION_LINE), unmerged = true).performClick()
            awaitTag(EditorTags.line(FUNCTION_LINE + 1))
            app.network.comeBack()
            awaitReconnected()
        }

    @Test
    fun `after the server restarts the use lines still fold`() =
        runComposeUiTest {
            val app = open()
            app.process.restart()
            awaitReconnected()
            awaitServer(app) { it.isRunning() }
            awaitTag(EditorTags.foldMarker(FIRST_USE), unmerged = true, timeout = SERVER_START_MS).performClick()
            awaitNoTag(EditorTags.line(LAST_USE))
        }
}
