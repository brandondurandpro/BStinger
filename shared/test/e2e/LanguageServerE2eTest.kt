package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.e2e.FakePhpServers.Companion.FAKE
import org.example.kotlineditor.e2e.FakePhpServers.Companion.NEEDS_NODE
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val CHILD_BYTES = 96L * 1024 * 1024

/** Language servers through the UI: choice of the PHP server, status bar, Language Servers window. */
@OptIn(ExperimentalTestApi::class)
class LanguageServerE2eTest {
    private val project = FakePhpServers.project()
    private val created = mutableListOf<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun servers(vararg options: String) = FakePhpServers(*options).also { created += it }

    @Test
    fun `the first PHP file offers the choice, and the chosen server starts`() =
        runComposeUiTest {
            val servers = servers()
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("README.md")
            awaitNoTag(Tags.LSP_CHOICE, timeout = 1_000)

            openFromTree("src/index.php")
            awaitTag(Tags.LSP_CHOICE)
            awaitTag(Tags.LSP_CHOICE_OPEN).performClick()
            awaitTag(Tags.LSP_CHOICE_DIALOG)
            // The recommended server, the first that can run, is selected: its advantages are shown.
            assertContains(textOf(Tags.LSP_CHOICE_DETAILS), "Starts in a second")
            assertContains(textOf(Tags.lspChoiceOption(NEEDS_NODE)), "Node.js not found")
            onNodeWithTag(Tags.lspChoiceOption(NEEDS_NODE)).performClick()
            awaitCondition { textOf(Tags.lspChoiceDetail(NEEDS_NODE)).contains("Node.js not found") }
            onNodeWithTag(Tags.lspChoiceUse(NEEDS_NODE)).assertIsNotEnabled()
            assertTrue(app.languageServers.isEmpty(), "nothing starts before the choice")

            onNodeWithTag(Tags.lspChoiceOption(FAKE)).performClick()
            awaitTag(Tags.lspChoiceUse(FAKE)).performClick()
            awaitNoTag(Tags.LSP_CHOICE_DIALOG)
            awaitNoTag(Tags.LSP_CHOICE)
            val running = awaitServer(app) { it.state == LanguageServerState.Running && it.memory != null }
            assertEquals(project.path, running.root)
            assertTrue(isAlive(running.pid!!))
            assertContains(servers.saved(), "php=fake")
            awaitCondition { textOf(Tags.statusLanguageServer(running.id)).matches(Regex("PHP · \\d+ MB")) }
            awaitCondition { textOf(Tags.STATUS_SERVER_MEMORY).matches(Regex("Server \\d+ MB")) }
        }

    @Test
    fun `the banner uses the recommended server in one click`() =
        runComposeUiTest {
            val servers = servers()
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/index.php")
            awaitTag(Tags.LSP_CHOICE_QUICK).performClick()
            awaitNoTag(Tags.LSP_CHOICE)
            awaitServer(app) { it.isRunning() }
            assertContains(servers.saved(), "php=fake")
        }

    @Test
    fun `the dialog is driven with the keyboard, and Escape keeps the banner`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().settings)
            openFromTree("src/index.php")
            awaitTag(Tags.LSP_CHOICE_OPEN).performClick()
            val dialog = awaitTag(Tags.LSP_CHOICE_DIALOG)
            dialog.performKeyInput { pressKey(Key.DirectionDown) }
            awaitTag(Tags.lspChoiceDetail(NEEDS_NODE))
            dialog.performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(Tags.LSP_CHOICE_DIALOG)
            awaitTag(Tags.LSP_CHOICE)
            assertTrue(app.languageServers.isEmpty())

            awaitTag(Tags.LSP_CHOICE_OPEN).performClick()
            awaitTag(Tags.LSP_CHOICE_DIALOG).performKeyInput { pressKey(Key.Enter) }
            awaitNoTag(Tags.LSP_CHOICE)
            awaitServer(app) { it.isRunning() }
        }

    @Test
    fun `cancel closes the dialog without choosing`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().settings)
            openFromTree("src/index.php")
            awaitTag(Tags.LSP_CHOICE_OPEN).performClick()
            awaitTag(Tags.LSP_CHOICE_CANCEL).performClick()
            awaitNoTag(Tags.LSP_CHOICE_DIALOG)
            awaitTag(Tags.LSP_CHOICE)
            assertTrue(app.languageServers.isEmpty())
        }

    @Test
    fun `not now starts no server and does not ask again during the session`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().settings)
            openFromTree("src/index.php")
            awaitTag(Tags.LSP_CHOICE_NOT_NOW).performClick()
            awaitNoTag(Tags.LSP_CHOICE)
            openFromTree("src/util.php")
            awaitNoTag(Tags.LSP_CHOICE, timeout = 2_000)
            Thread.sleep(500)
            waitForIdle()
            awaitNoTag(Tags.LSP_CHOICE)
            assertTrue(app.languageServers.isEmpty())
        }

    @Test
    fun `a saved choice starts the server without asking, one process for every PHP file`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().chosen().settings)
            openFromTree("src/index.php")
            openFromTree("src/util.php")
            val running = awaitServer(app) { it.state == LanguageServerState.Running }
            awaitNoTag(Tags.LSP_CHOICE)
            assertEquals(1, app.languageServers.size)
            assertEquals(1, running.users)
        }

    @Test
    fun `restart from the window, clicked twice, restarts once`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().chosen().settings)
            openFromTree("src/index.php")
            val first = awaitServer(app) { it.state == LanguageServerState.Running }
            awaitTag(Tags.statusLanguageServer(first.id)).performClick()
            awaitTag(Tags.LANGUAGE_SERVERS)
            onNodeWithTag(Tags.languageServerRestart(first.id)).performClick()
            onNodeWithTag(Tags.languageServerRestart(first.id)).performClick()
            val second = awaitServer(app) { it.generation > first.generation && it.isRunning() }
            Thread.sleep(1_000)
            waitForIdle()
            assertEquals(first.generation + 1, app.languageServers.single().generation)
            assertNotEquals(first.pid, second.pid)
            awaitCondition { !isAlive(first.pid!!) }
        }

    @Test
    fun `stop and start from the window`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().chosen().settings)
            openFromTree("src/index.php")
            val running = awaitServer(app) { it.state == LanguageServerState.Running }
            awaitTag(Tags.statusLanguageServer(running.id)).performClick()
            awaitTag(Tags.languageServerStop(running.id)).performClick()
            awaitServer(app) { it.state == LanguageServerState.Stopped }
            awaitCondition { textOf(Tags.statusLanguageServer(running.id)) == "PHP · stopped" }
            awaitCondition { !isAlive(running.pid!!) }
            awaitTag(Tags.languageServerStart(running.id)).performClick()
            awaitServer(app) { it.state == LanguageServerState.Running && it.pid != running.pid }
        }

    @Test
    fun `a server crashing in a loop is shown crashed with its last error`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers("--crash-on-start").chosen().settings)
            openFromTree("src/index.php")
            val crashed = awaitServer(app) { it.state == LanguageServerState.Crashed }
            awaitCondition { textOf(Tags.statusLanguageServer(crashed.id)) == "PHP · crashed" }
            awaitTag(Tags.statusLanguageServer(crashed.id)).performClick()
            awaitCondition { textOf(Tags.languageServerMessage(crashed.id)).contains("Fatal error: fake crash") }
            awaitTag(Tags.languageServerStart(crashed.id))
        }

    @Test
    fun `the log shows what the server wrote on stderr`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers("--ask-configuration").chosen().settings)
            openFromTree("src/index.php")
            val running = awaitServer(app) { it.state == LanguageServerState.Running }
            awaitTag(Tags.statusLanguageServer(running.id)).performClick()
            awaitTag(Tags.languageServerLog(running.id)).performClick()
            awaitTag(Tags.LANGUAGE_SERVERS_LOG)
            awaitCondition { textOf(Tags.LANGUAGE_SERVERS_LOG).contains("response \"configuration-1\": [null]") }
        }

    @Test
    fun `indexing progress is shown in the status bar`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers("--progress", "4").chosen().settings)
            openFromTree("src/index.php")
            val indexing = awaitServer(app) { it.state == LanguageServerState.Indexing }
            awaitCondition { textOf(Tags.statusLanguageServer(indexing.id)).matches(Regex("PHP · indexing \\d+ %")) }
            awaitServer(app) { it.state == LanguageServerState.Running }
        }

    @Test
    fun `closing the PHP files stops the server after the idle delay`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().chosen().settings)
            openFromTree("src/index.php")
            val running = awaitServer(app) { it.state == LanguageServerState.Running }
            app.state.closeProject()
            awaitCondition(SERVER_START_MS) { app.languageServers.isEmpty() }
            awaitCondition { !isAlive(running.pid!!) }
        }

    @Test
    fun `memory of the server's child processes is counted`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers("--child-mb", "96").chosen().settings)
            openFromTree("src/index.php")
            val os = System.getProperty("os.name")
            // Children are only measured on Linux and Windows.
            if (os.startsWith("Linux") || os.startsWith("Windows")) {
                val counted = awaitServer(app) { it.privateBytes() > CHILD_BYTES }
                assertTrue(counted.memory!!.processCount >= 2)
            }
        }
}

private fun org.example.kotlineditor.model.LanguageServerInfo.privateBytes() = memory?.privateBytes ?: 0L
