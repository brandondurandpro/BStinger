package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.LspLevel
import org.example.kotlineditor.state.LspLevelSource
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The level of the language servers (Off, Basic, Full) chosen in the top bar for a conversation, a project or all. */
@OptIn(ExperimentalTestApi::class)
class LspLevelE2eTest {
    private val servers = FakePhpServers().chosen()
    private val project = FakePhpServers.project().apply { commitAll() }
    private var preferences = agentLanguageServerPreferences()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.launch(global: LspLevel? = null): E2eApp {
        preferences = agentLanguageServerPreferences(global)
        val app = launchWithProject(project, preferences, languageServers = servers.settings)
        openFromTree("src/index.php")
        awaitServer(app) { it.isRunning() }
        return app
    }

    private fun ComposeUiTest.choose(level: LspLevel, scope: LspLevelSource) {
        onNodeWithTag(AppTags.LSP_LEVEL_BUTTON).performClick()
        awaitTag(AppTags.lspLevel(level.name)).performClick()
        awaitTag(AppTags.lspScope(scope.name)).performClick()
        awaitNoTag(AppTags.lspScope(scope.name))
    }

    private fun ComposeUiTest.awaitStopped(app: E2eApp) =
        awaitCondition(SERVER_START_MS) { app.languageServers.none { it.isRunning() } }

    @Test
    fun `Off for the project stops the servers, and Full starts them again`() =
        runComposeUiTest {
            val app = launch()
            val item = LanguageServerTags.statusLanguageServer(awaitServer(app) { it.isRunning() }.id)
            awaitTag(item)
            choose(LspLevel.Off, LspLevelSource.Project)
            awaitStopped(app)
            awaitNoTag(item)
            assertEquals(LspLevelSource.Project, app.project.lspLevelSource)
            choose(LspLevel.Full, LspLevelSource.Project)
            awaitServer(app) { it.isRunning() }
            awaitTag(item)
        }

    @Test
    fun `Basic keeps the servers running`() =
        runComposeUiTest {
            val app = launch()
            choose(LspLevel.Basic, LspLevelSource.Project)
            assertEquals(LspLevel.Basic, app.project.lspLevel)
            assertEquals(false, app.project.insight.decorated)
            awaitServer(app) { it.isRunning() }
            choose(LspLevel.Full, LspLevelSource.Project)
            awaitCondition { app.project.insight.decorated }
        }

    @Test
    fun `the global level applies to a project that chose none, and is kept`() =
        runComposeUiTest {
            val app = launch()
            choose(LspLevel.Off, LspLevelSource.Global)
            awaitStopped(app)
            assertEquals(LspLevelSource.Global, app.project.lspLevelSource)
            assertEquals(LspLevel.Off, app.state.settings.lspLevel)
            awaitCondition(30_000) { preferences.load()[PreferenceKeys.LSP_LEVEL] == LspLevel.Off.name }
        }

    @Test
    fun `the project level is kept for the project and wins over the global one`() =
        runComposeUiTest {
            val app = launch(global = LspLevel.Full)
            choose(LspLevel.Off, LspLevelSource.Project)
            awaitStopped(app)
            val key = PreferenceKeys.LSP_LEVEL_PREFIX + app.project.root.path
            awaitCondition(30_000) { preferences.load()[key] == LspLevel.Off.name }
            assertEquals(LspLevel.Full, app.state.settings.lspLevel)
        }

    @Test
    fun `the level of a conversation applies while it is shown, the project's the rest of the time`() =
        runComposeUiTest {
            val app = launch()
            awaitTag(AgentTags.MODE_AGENT).performClick()
            clickNewConversation(app)
            awaitTag(AgentTags.AGENT_PROMPT)
            choose(LspLevel.Off, LspLevelSource.Conversation)
            awaitStopped(app)
            assertEquals(LspLevelSource.Conversation, app.project.lspLevelSource)
            awaitTag(AgentTags.MODE_IDE).performClick()
            awaitServer(app) { it.isRunning() }
            assertEquals(LspLevelSource.Global, app.project.lspLevelSource)
            val conversations = app.project.agent.conversations
            val levels = conversations.map { it.lspLevel }
            assertTrue(LspLevel.Off in levels)
        }

    @Test
    fun `the choice for a conversation is offered only in agent mode`() =
        runComposeUiTest {
            launch()
            onNodeWithTag(AppTags.LSP_LEVEL_BUTTON).performClick()
            awaitTag(AppTags.lspLevel(LspLevel.Off.name)).performClick()
            awaitTag(AppTags.lspScope(LspLevelSource.Project.name))
            awaitNoTag(AppTags.lspScope(LspLevelSource.Conversation.name))
        }

    @Test
    fun `the round trip Off then Full changes nothing on the disk`() =
        runComposeUiTest {
            val app = launch()
            val before = DiskSnapshot.take(project)
            choose(LspLevel.Off, LspLevelSource.Project)
            awaitStopped(app)
            choose(LspLevel.Full, LspLevelSource.Project)
            awaitServer(app) { it.isRunning() }
            before.assertUnchanged(project)
        }
}
