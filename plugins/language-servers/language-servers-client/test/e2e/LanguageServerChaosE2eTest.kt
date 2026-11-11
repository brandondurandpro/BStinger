package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Language servers when the network or the IDE server fails. */
@OptIn(ExperimentalTestApi::class)
class LanguageServerChaosE2eTest {
    private val project = FakePhpServers.project()
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `when the IDE server restarts no language server survives, and it starts again`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
            openFromTree("src/index.php")
            val before = awaitServer(app) { it.state == LanguageServerState.Running }
            app.process.restart()
            awaitCondition { !isAlive(before.pid!!) }
            // The new IDE server knows nothing of the open files until the IDE declares them again.
            val after = awaitServer(app) { it.state == LanguageServerState.Running && it.pid != before.pid }
            assertEquals(1, app.languageServers.size)
            assertEquals(1, after.users)
            assertContains(servers.saved(), "php=fake")
            awaitNoTag(LanguageServerTags.LSP_CHOICE)
        }

    @Test
    fun `a crash while the network is down shows up once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
            openFromTree("src/index.php")
            val before = awaitServer(app) { it.state == LanguageServerState.Running }
            app.network.goDown()
            awaitReconnecting()
            ProcessHandle.of(before.pid!!).ifPresent { it.destroyForcibly() }
            Thread.sleep(2_000)
            app.network.comeBack()
            awaitReconnected()
            val after = awaitServer(app) { it.isRunning() && it.generation > before.generation }
            assertEquals(1, after.recentCrashes)
            awaitCondition { textOf(LanguageServerTags.statusLanguageServer(after.id)).matches(Regex("PHP · \\d+ MB")) }
        }

    @Test
    fun `a restart whose answer is lost restarts once`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
            openFromTree("src/index.php")
            val first = awaitServer(app) { it.state == LanguageServerState.Running }
            awaitTag(LanguageServerTags.statusLanguageServer(first.id)).performClick()
            app.network.dropFromServer = true
            onNodeWithTag(LanguageServerTags.languageServerRestart(first.id)).performClick()
            // The server restarted it, but the answer was lost: the client sends the same request again.
            awaitCondition(SERVER_START_MS) { !isAlive(first.pid!!) }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            val second = awaitServer(app) { it.isRunning() && it.generation > first.generation }
            Thread.sleep(1_500)
            waitForIdle()
            assertEquals(first.generation + 1, app.languageServers.single().generation)
            assertNotEquals(first.pid, second.pid)
        }

    @Test
    fun `a slow link keeps the newest status`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
            openFromTree("src/index.php")
            val first = awaitServer(app) { it.state == LanguageServerState.Running }
            app.network.latencyMs = 300
            awaitTag(LanguageServerTags.statusLanguageServer(first.id)).performClick()
            awaitTag(LanguageServerTags.languageServerStop(first.id)).performClick()
            awaitServer(app) { it.state == LanguageServerState.Stopped }
            awaitTag(LanguageServerTags.languageServerStart(first.id)).performClick()
            val restarted = awaitServer(app) { it.isRunning() && it.generation > first.generation }
            Thread.sleep(2_000)
            waitForIdle()
            assertEquals(LanguageServerState.Running, app.languageServers.single().state)
            assertEquals(restarted.generation, app.languageServers.single().generation)
        }
}
