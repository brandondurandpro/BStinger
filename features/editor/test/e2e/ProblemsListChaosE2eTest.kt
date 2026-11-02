package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test

/** The problems list when the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class ProblemsListChaosE2eTest {
    private val project = codeInsightProject().apply { file("src/Broken.php", BROKEN_PHP) }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        openFromTree("src/Broken.php")
        awaitServer(app) { it.isRunning() }
        awaitCondition(SERVER_START_MS) { textOf(EditorTags.PROBLEMS_ERRORS) == "2" }
        return app
    }

    private fun E2eApp.caret(): TextPos? =
        project.editor.activeTab
            ?.document
            ?.caret

    @Test
    fun `while the network is down the list shows the problems last known and still goes to them`() =
        runComposeUiTest {
            val app = open()
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(EditorTags.PROBLEMS).performClick()
            awaitTag(EditorTags.problem(2))
            onNodeWithTag(EditorTags.problem(2)).performClick()
            awaitCondition { app.caret() == TextPos(SECOND_ERROR_LINE, 0) }
            app.network.comeBack()
            awaitReconnected()
        }

    @Test
    fun `across a server restart the open list keeps its problems and still goes to them`() =
        runComposeUiTest {
            val app = open()
            onNodeWithTag(EditorTags.PROBLEMS).performClick()
            awaitTag(EditorTags.problem(2))
            app.process.restart()
            awaitReconnected()
            awaitServer(app) { it.isRunning() }
            // Still listed (nothing lost), and still going to the right place.
            awaitTag(EditorTags.problem(2), timeout = SERVER_START_MS)
            onNodeWithTag(EditorTags.problemGo(0), useUnmergedTree = true).performClick()
            awaitCondition { app.caret() == TextPos(3, 0) }
        }
}
