package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The TypeScript server when the network or the IDE server fails. */
@OptIn(ExperimentalTestApi::class)
class TypeScriptServerChaosE2eTest {
    private val project = FakePhpServers.webProject()
    private val servers = FakePhpServers()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `when the IDE server restarts the TypeScript server starts again without asking`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
            openFromTree("src/view.tsx")
            val before = awaitServer(app) { it.state == LanguageServerState.Running }
            app.process.restart()
            awaitCondition { !isAlive(before.pid!!) }
            val after = awaitServer(app) { it.state == LanguageServerState.Running && it.pid != before.pid }
            assertEquals(1, app.languageServers.size)
            assertEquals(1, after.users)
            awaitNoTag(LanguageServerTags.LSP_CHOICE)
        }

    @Test
    fun `a crash while the network is down shows up once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
            openFromTree("src/app.ts")
            val before = awaitServer(app) { it.state == LanguageServerState.Running }
            app.network.goDown()
            awaitReconnecting()
            ProcessHandle.of(before.pid!!).ifPresent { it.destroyForcibly() }
            Thread.sleep(2_000)
            app.network.comeBack()
            awaitReconnected()
            val after = awaitServer(app) { it.isRunning() && it.generation > before.generation }
            assertEquals(1, after.recentCrashes)
        }

    @Test
    fun `when the IDE server restarts both servers of a Tailwind project start again`() =
        runComposeUiTest {
            val tailwind = FakePhpServers.tailwindProject()
            try {
                val app = launchWithProject(tailwind, chaos = true, languageServers = servers.settings)
                openFromTree("src/view.tsx")
                awaitCondition(SERVER_START_MS) { app.languageServers.count { it.isRunning() } == 2 }
                val before = app.languageServers.mapNotNull { it.pid }
                app.process.restart()
                before.forEach { pid -> awaitCondition { !isAlive(pid) } }
                awaitCondition(SERVER_START_MS) {
                    app.languageServers.count { it.isRunning() && it.pid !in before } == 2
                }
                assertEquals(2, app.languageServers.size)
            } finally {
                tailwind.delete()
            }
        }
}
