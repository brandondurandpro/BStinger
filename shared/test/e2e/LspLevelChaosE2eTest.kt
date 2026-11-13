package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.LspLevel
import org.example.kotlineditor.state.LspLevelSource
import org.example.kotlineditor.ui.AppTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** The level of the language servers when the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class LspLevelChaosE2eTest {
    private val servers = FakePhpServers().chosen()
    private val project = FakePhpServers.project()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.open(): E2eApp {
        val preferences = agentLanguageServerPreferences()
        val app = launchWithProject(project, preferences, chaos = true, languageServers = servers.settings)
        openFromTree("src/index.php")
        awaitServer(app) { it.isRunning() }
        return app
    }

    private fun ComposeUiTest.chooseOff() {
        onNodeWithTag(AppTags.LSP_LEVEL_BUTTON).performClick()
        awaitTag(AppTags.lspLevel(LspLevel.Off.name)).performClick()
        awaitTag(AppTags.lspScope(LspLevelSource.Project.name)).performClick()
    }

    private fun usesOf(app: E2eApp) = app.localLanguageServers.calls.filter { it.method == "use" }

    @Test
    fun `Off chosen while the network is down stops the servers once it is back`() =
        runComposeUiTest {
            val app = open()
            app.network.goDown()
            awaitReconnecting()
            chooseOff()
            app.network.comeBack()
            awaitReconnected()
            awaitCondition(SERVER_START_MS) { app.languageServers.none { it.isRunning() } }
        }

    @Test
    fun `a server restarted while Off does not bring the language servers back`() =
        runComposeUiTest {
            val app = open()
            chooseOff()
            awaitCondition(SERVER_START_MS) { app.languageServers.none { it.isRunning() } }
            val before = usesOf(app).size
            app.process.restart()
            awaitReconnected()
            // The UI declares again after the reconnection, and declares no file.
            awaitCondition(SERVER_START_MS) { usesOf(app).size > before }
            val declared = usesOf(app).drop(before)
            assertTrue(declared.all { it.files.isEmpty() }, "files declared while Off: $declared")
        }
}
