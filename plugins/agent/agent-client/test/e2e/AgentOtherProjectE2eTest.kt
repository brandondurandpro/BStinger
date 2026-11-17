package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.WorkMode
import org.example.kotlineditor.state.toPreferences
import org.example.kotlineditor.testkit.acp.FakeSessions
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.AppTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A new conversation works in no project: it starts at once in a new empty folder of the server, under the group "No
 * project". Choosing a project in the prompt bar moves it there, in the same window, and asks from which branch; the
 * folders chosen get a group of their own, which can be removed.
 */
@OptIn(ExperimentalTestApi::class)
class AgentOtherProjectE2eTest {
    private val project = SampleProject.standard()
    private val other = SampleProject.standard()
    private val fresh = SampleProject()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        other.delete()
        fresh.delete()
        store.toFile().deleteRecursively()
    }

    private fun ComposeUiTest.launch(vararg recent: String): E2eApp {
        project.commitAll()
        other.commitAll()
        val app = launchApp(recent = recent.toList(), preferences = agentPreferences("--store", "$store"))
        awaitTag(AppTags.recentProject(project.path)).performClick()
        awaitTag(AgentTags.MODE_AGENT).performClick()
        return app
    }

    // Starts a conversation, then moves it to the project whose row of the menu has [row].
    private fun ComposeUiTest.moveTo(app: E2eApp, row: String): Conversation {
        val conversation = clickNewConversationWithoutProject(app)
        openFolderMenu()
        awaitTag(row).performClick()
        awaitCondition { !conversation.scratch }
        return conversation
    }

    // The question of the repository comes: the project's own branch is taken.
    private fun ComposeUiTest.takeProjectBranch() {
        awaitTag(AgentTags.AGENT_BASE_PICKER)
        awaitTag(AgentTags.AGENT_BASE_PROJECT).performClick()
        awaitNoTag(AgentTags.AGENT_BASE_PICKER)
    }

    @Test
    fun `plus starts a conversation at once, in an empty folder of the server that is no project`() =
        runComposeUiTest(testTimeout = 5.minutes) {
            val app = launch(project.path)
            var windows = 0
            app.state.onNewWindow = { windows++ }
            val conversation = clickNewConversationWithoutProject(app)
            assertTrue(conversation.scratch)
            assertEquals(0, windows)
            awaitNoTag(AgentTags.AGENT_BASE_PICKER)
            awaitTag(AgentTags.AGENT_SCRATCH_GROUP)
            assertContains(textOf(AgentTags.AGENT_FOLDER), "No project")
            val folder = checkNotNull(conversation.folder)
            assertTrue(folder.startsWith(app.process.dataDirectory.canonicalPath), "under the data of the server: $folder")
            assertTrue(java.io.File(folder).isDirectory)
            assertTrue(java.io.File(folder).list().orEmpty().isEmpty(), "empty")
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: $folder")
        }

    @Test
    fun `each conversation without a project has a folder of its own`() =
        runComposeUiTest {
            val app = launch(project.path)
            val first = clickNewConversationWithoutProject(app)
            val second = clickNewConversationWithoutProject(app)
            assertNotEquals(first.folder, second.folder)
            assertEquals(2, app.project.agent.conversations.count { it.scratch })
        }

    @Test
    fun `the project of the window is chosen in the prompt bar, which asks the branch, and the agent works there`() =
        runComposeUiTest {
            val app = launch(project.path)
            val conversation = moveTo(app, AgentTags.AGENT_FOLDER_THIS_PROJECT)
            takeProjectBranch()
            assertEquals(null, conversation.folder)
            assertEquals(WorkMode.Direct, conversation.mode)
            awaitTag(AgentTags.AGENT_PROJECT_GROUP)
            awaitNoTag(AgentTags.AGENT_SCRATCH_GROUP)
            assertContains(textOf(AgentTags.AGENT_FOLDER), "Project root")
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: ${project.path}")
        }

    @Test
    fun `a branch chosen for the project starts the worktree of that branch`() =
        runComposeUiTest(testTimeout = 5.minutes) {
            val app = launch(project.path)
            project.git("branch", "feature")
            val conversation = moveTo(app, AgentTags.AGENT_FOLDER_THIS_PROJECT)
            awaitTag(AgentTags.agentBase("feature")).performClick()
            awaitCondition(AGENT_TIMEOUT) { conversation.mode == WorkMode.Worktree && !conversation.preparingWorktree }
            val worktree = checkNotNull(conversation.workspace).root.path
            assertNotEquals(project.path, worktree)
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: $worktree")
        }

    @Test
    fun `another recent project is chosen in the same window, under its own group, and the agent works there`() =
        runComposeUiTest {
            val app = launch(project.path, other.path)
            var windows = 0
            app.state.onNewWindow = { windows++ }
            val conversation = moveTo(app, AgentTags.agentFolderProject(other.path))
            takeProjectBranch()
            assertEquals(other.path, conversation.folder)
            assertEquals(0, windows, "no window opens")
            assertEquals(project.path, app.project.root.path, "this window keeps its project")
            awaitTag(AgentTags.agentFolderGroup(other.path))
            val workspace = checkNotNull(conversation.workspace)
            assertEquals(other.path, workspace.root.path)
            awaitCondition(AGENT_TIMEOUT) { workspace.checkedOutBranch != null }
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: ${other.path}")
        }

    @Test
    fun `a folder that is no repository starts without asking a branch`() =
        runComposeUiTest {
            val app = launch(project.path, fresh.path)
            val conversation = moveTo(app, AgentTags.agentFolderProject(fresh.path))
            awaitNoTag(AgentTags.AGENT_BASE_PICKER)
            assertEquals(fresh.path, conversation.folder)
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: ${fresh.path}")
        }

    @Test
    fun `a project that no longer exists is refused, and the conversation stays without a project`() =
        runComposeUiTest {
            val app = launch(project.path, other.path)
            val conversation = clickNewConversationWithoutProject(app)
            openFolderMenu()
            other.delete()
            awaitTag(AgentTags.agentFolderProject(other.path)).performClick()
            awaitTag(AgentTags.AGENT_FOLDER_ERROR)
            assertTrue(conversation.scratch)
            assertEquals(1, app.project.agent.conversations.size)
        }

    @Test
    fun `escape in the question of the branch keeps the project's own branch`() =
        runComposeUiTest {
            val app = launch(project.path)
            val conversation = moveTo(app, AgentTags.AGENT_FOLDER_THIS_PROJECT)
            awaitTag(AgentTags.AGENT_BASE_PICKER)
            runOnIdle { app.project.agent.cancelBasePicker() }
            awaitNoTag(AgentTags.AGENT_BASE_PICKER)
            assertEquals(WorkMode.Direct, conversation.mode)
            assertFalse(conversation.scratch)
        }

    @Test
    fun `a conversation without a project comes back with the history, after a restart, under its group`() =
        runComposeUiTest {
            project.commitAll()
            val parent = Files.createTempDirectory("agent-scratch")
            try {
                val folder = parent.resolve("conversation-old").toString()
                val session = pastSession(store, folder, listOf("echo from nowhere" to "from nowhere "))
                val values = agentPreferences("--store", "$store").load() +
                    mapOf(PreferenceKeys.AGENT_SCRATCH_PREFIX + project.path to parent.toString())
                val app = launchWithProject(project, InMemoryPreferenceStore(values))
                val conversation = awaitHistory(app, session)
                assertTrue(conversation.scratch)
                assertEquals(folder, conversation.folder)
                awaitTag(AgentTags.AGENT_SCRATCH_GROUP)
                assertContains(app.project.agent.toPreferences()[PreferenceKeys.AGENT_SCRATCH_PREFIX + project.path].orEmpty(), parent.toString())
            } finally {
                parent.toFile().deleteRecursively()
            }
        }

    // A conversation of the other folder that has talked: its session is the agent's, in its store.
    private fun ComposeUiTest.talkInOther(app: E2eApp): Conversation {
        val conversation = moveTo(app, AgentTags.agentFolderProject(other.path))
        takeProjectBranch()
        sendPrompt("echo hi from other")
        awaitAnswer(conversation, "hi from other")
        awaitCondition { conversation.session != null }
        awaitIdle(conversation)
        return conversation
    }

    @Test
    fun `a folder is removed from the list, its conversations close, and the agent keeps their sessions`() =
        runComposeUiTest(testTimeout = 5.minutes) {
            val app = launch(project.path, other.path)
            val conversation = talkInOther(app)
            val session = checkNotNull(conversation.session)
            awaitTag(AgentTags.agentFolderRemove(other.path)).performClick()
            awaitNoTag(AgentTags.agentFolderGroup(other.path))
            awaitNoTag(AgentTags.agentConversation(conversation.id))
            assertTrue(app.project.agent.conversations.none { it.folder == other.path })
            assertEquals("", app.project.agent.toPreferences()[PreferenceKeys.AGENT_FOLDERS_PREFIX + project.path])
            assertTrue(FakeSessions(store.toFile()).list(other.path).any { it.id == session }, "the agent keeps the session")
        }

    @Test
    fun `the project of the window and the group without a project have no button to remove them`() =
        runComposeUiTest {
            val app = launch(project.path)
            clickNewConversationWithoutProject(app)
            awaitTag(AgentTags.AGENT_SCRATCH_GROUP)
            assertTrue(onAllNodes(hasTestTagPrefix("agent.group.folder.remove:")).fetchSemanticsNodes().isEmpty())
            assertNotNull(app.project.agent.current)
        }

    @Test
    fun `a folder that was removed comes back with its sessions when a conversation works in it again`() =
        runComposeUiTest(testTimeout = 5.minutes) {
            val app = launch(project.path, other.path)
            val conversation = talkInOther(app)
            val session = checkNotNull(conversation.session)
            awaitTag(AgentTags.agentFolderRemove(other.path)).performClick()
            awaitNoTag(AgentTags.agentFolderGroup(other.path))
            moveTo(app, AgentTags.agentFolderProject(other.path))
            takeProjectBranch()
            awaitTag(AgentTags.agentFolderGroup(other.path))
            awaitCondition(AGENT_TIMEOUT) { app.project.agent.conversations.any { it.session == session } }
            assertEquals(other.path, app.project.agent.conversations.firstOrNull { it.session == session }?.folder)
        }

    @Test
    fun `removing a folder whose agent is at work stops it, and nothing is left running`() =
        runComposeUiTest(testTimeout = 5.minutes) {
            val app = launch(project.path, other.path)
            val conversation = moveTo(app, AgentTags.agentFolderProject(other.path))
            takeProjectBranch()
            sendPrompt("slow")
            awaitCondition(AGENT_TIMEOUT) { conversation.busy }
            awaitTag(AgentTags.agentFolderRemove(other.path)).performClick()
            awaitNoTag(AgentTags.agentFolderGroup(other.path))
            assertTrue(app.project.agent.conversations.isEmpty())
            // The window is usable: a conversation of the project starts and answers.
            clickNewConversation(app)
            val here = checkNotNull(app.project.agent.current)
            sendPrompt("echo still fine")
            awaitAnswer(here, "still fine")
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
