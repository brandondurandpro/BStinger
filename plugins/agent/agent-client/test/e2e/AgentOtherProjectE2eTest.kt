package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.WorkMode
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.RemoteTags
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A new conversation can start in another project: it opens in its own window, which asks which branch to start from. */
@OptIn(ExperimentalTestApi::class)
class AgentOtherProjectE2eTest {
    private val project = SampleProject.standard()
    private val other = SampleProject.standard()
    private val fresh = SampleProject()

    @AfterTest
    fun cleanup() {
        project.delete()
        other.delete()
        fresh.delete()
    }

    private fun ComposeUiTest.openPicker(app: E2eApp) {
        project.commitAll()
        other.commitAll()
        awaitTag(AppTags.recentProject(project.path)).performClick()
        awaitTag(AgentTags.MODE_AGENT).performClick()
        awaitTag(AgentTags.AGENT_NEW).performClick()
        awaitTag(AgentTags.AGENT_BASE_PICKER)
        assertEquals(0, app.project.agent.conversations.size, "nothing starts before the answer")
    }

    @Test
    fun `another recent project opens in a new window and no conversation starts here`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path, other.path), preferences = agentPreferences())
            val windows = mutableListOf<String?>()
            app.state.onNewWindow = { windows.add(it) }
            openPicker(app)
            awaitTag(AgentTags.AGENT_BASE_OTHER_PROJECT).performClick()
            awaitNoTag(AgentTags.agentBaseProject(project.path))
            awaitTag(AgentTags.agentBaseProject(other.path)).performClick()
            awaitNoTag(AgentTags.AGENT_BASE_PICKER)
            assertEquals(listOf<String?>(other.path), windows)
            assertTrue(app.project.agent.conversations.isEmpty(), "no conversation starts in this window")
        }

    @Test
    fun `the search narrows the projects and Enter takes the highlighted one`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path, other.path), preferences = agentPreferences())
            val windows = mutableListOf<String?>()
            app.state.onNewWindow = { windows.add(it) }
            openPicker(app)
            awaitTag(AgentTags.AGENT_BASE_OTHER_PROJECT).performClick()
            val name = other.path.substringAfterLast('/')
            awaitTag(AgentTags.AGENT_BASE_SEARCH).performTextInput(name)
            awaitTag(AgentTags.AGENT_BASE_SEARCH).performKeyInput { pressKey(Key.Enter) }
            awaitCondition { windows.size == 1 }
            assertEquals(other.path, windows.firstOrNull())
        }

    @Test
    fun `Escape closes the project list and the picker, and no window opens`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path, other.path), preferences = agentPreferences())
            val windows = mutableListOf<String?>()
            app.state.onNewWindow = { windows.add(it) }
            openPicker(app)
            awaitTag(AgentTags.AGENT_BASE_OTHER_PROJECT).performClick()
            awaitTag(AgentTags.agentBaseProject(other.path))
            awaitTag(AgentTags.AGENT_BASE_SEARCH).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(AgentTags.AGENT_BASE_PICKER)
            assertTrue(windows.isEmpty())
            assertTrue(app.project.agent.conversations.isEmpty())
        }

    @Test
    fun `without a desktop app there is no other project to choose`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path, other.path), preferences = agentPreferences())
            openPicker(app)
            awaitNoTag(AgentTags.AGENT_BASE_OTHER_PROJECT)
        }

    @Test
    fun `with no other recent project the list is empty but a directory can be browsed`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path), preferences = agentPreferences())
            app.state.onNewWindow = { }
            openPicker(app)
            awaitTag(AgentTags.AGENT_BASE_OTHER_PROJECT).performClick()
            awaitTag(AgentTags.AGENT_BASE_BROWSE)
            awaitNoTag(AgentTags.agentBaseProject(project.path))
        }

    @Test
    fun `Browse picks a directory that was never opened, which opens in a new window`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path), preferences = agentPreferences())
            val windows = mutableListOf<String?>()
            app.state.onNewWindow = { windows.add(it) }
            openPicker(app)
            awaitTag(AgentTags.AGENT_BASE_OTHER_PROJECT).performClick()
            awaitTag(AgentTags.AGENT_BASE_BROWSE).performClick()
            awaitNoTag(AgentTags.AGENT_BASE_PICKER)
            awaitTag(RemoteTags.OPEN_PROJECT)
            showDirectoryInProjectPicker(other.path)
            awaitCondition { runCatching { onNodeWithTag(RemoteTags.OPEN_PROJECT_OPEN).assertIsEnabled() }.isSuccess }
            onNodeWithTag(RemoteTags.OPEN_PROJECT_OPEN).performClick()
            awaitNoTag(RemoteTags.OPEN_PROJECT)
            assertEquals(listOf<String?>(other.path), windows)
            assertEquals(project.path, app.project.root.path, "this window keeps its project")
            assertTrue(app.project.agent.conversations.isEmpty())
        }

    @Test
    fun `Browse can create the folder of the project, which opens in a new window`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path), preferences = agentPreferences())
            val windows = mutableListOf<String?>()
            app.state.onNewWindow = { windows.add(it) }
            openPicker(app)
            awaitTag(AgentTags.AGENT_BASE_OTHER_PROJECT).performClick()
            awaitTag(AgentTags.AGENT_BASE_BROWSE).performClick()
            showDirectoryInProjectPicker(other.path)
            onNodeWithTag(RemoteTags.OPEN_PROJECT_NEW_FOLDER).performClick()
            awaitTag(RemoteTags.OPEN_PROJECT_NEW_NAME).performTextInput("brand-new")
            onNodeWithTag(RemoteTags.OPEN_PROJECT_NEW_CREATE).performClick()
            val created = File(other.path, "brand-new")
            awaitCondition { shownDirectoryInProjectPicker() == created.path }
            awaitCondition { runCatching { onNodeWithTag(RemoteTags.OPEN_PROJECT_OPEN).assertIsEnabled() }.isSuccess }
            onNodeWithTag(RemoteTags.OPEN_PROJECT_OPEN).performClick()
            awaitCondition { windows.size == 1 }
            assertEquals(created.path, windows.firstOrNull())
            assertTrue(created.isDirectory, "the folder exists")
        }

    @Test
    fun `cancelling the directory dialog opens no window`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path), preferences = agentPreferences())
            val windows = mutableListOf<String?>()
            app.state.onNewWindow = { windows.add(it) }
            openPicker(app)
            awaitTag(AgentTags.AGENT_BASE_OTHER_PROJECT).performClick()
            awaitTag(AgentTags.AGENT_BASE_BROWSE).performClick()
            awaitTag(RemoteTags.OPEN_PROJECT_CANCEL).performClick()
            awaitNoTag(RemoteTags.OPEN_PROJECT)
            assertTrue(windows.isEmpty())
            // The dialog is back to a plain Open Project: the next one opens in this window.
            assertFalse(app.state.session.projectPickerForNewWindow)
        }

    @Test
    fun `a window opened on a project shows agent mode and asks from which branch, changing nothing on disk`() =
        runComposeUiTest {
            other.commitAll()
            val before = DiskSnapshot.take(other)
            val app = launchApp(preferences = agentPreferences(), initialProject = other.path)
            awaitTag(AgentTags.AGENT_BASE_PICKER)
            assertEquals(other.path, assertNotNull(app.state.session.project).root.path)
            assertTrue(app.project.agentMode, "agent mode is shown")
            assertTrue(app.project.agent.conversations.isEmpty(), "nothing starts before the answer")
            before.assertUnchanged(other)
        }

    @Test
    fun `a window opened on a folder that is no repository starts its conversation without asking`() =
        runComposeUiTest {
            val app = launchApp(preferences = agentPreferences(), initialProject = fresh.path)
            awaitCondition { app.state.session.project?.agent?.current != null }
            awaitNoTag(AgentTags.AGENT_BASE_PICKER)
            assertEquals(WorkMode.Direct, assertNotNull(app.project.agent.current).mode)
        }
}
