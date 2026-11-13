package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.WorkMode
import org.example.kotlineditor.ui.AgentTags
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A new conversation in a Git repository asks which branch the agent starts from: the project's, a local or a remote one. */
@OptIn(ExperimentalTestApi::class)
class AgentBasePickerE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // A repository whose branch `other` holds a file that the checked out branch does not, and `origin/feature` too.
    private fun repositoryWithBranches() {
        project.commitAll()
        project.git("checkout", "-q", "-b", "other")
        project.file("only-on-other.txt", "other\n")
        project.git("add", "-A")
        project.git("commit", "-q", "-m", "other")
        val other = project.git("rev-parse", "HEAD").trim()
        project.git("checkout", "-q", "-")
        project.git("update-ref", "refs/remotes/origin/feature", other)
    }

    private fun ComposeUiTest.openPicker(app: E2eApp) {
        awaitTag(AgentTags.MODE_AGENT).performClick()
        awaitTag(AgentTags.AGENT_NEW).performClick()
        awaitTag(AgentTags.AGENT_BASE_PICKER)
        assertEquals(0, app.project.agent.conversations.size, "nothing starts before the answer")
    }

    private fun workspaceFile(conversation: Conversation, name: String): Boolean {
        val root = conversation.workspace?.root?.path ?: return false
        return File(root, name).exists()
    }

    @Test
    fun `a local branch is the base of the worktree of the new conversation`() =
        runComposeUiTest {
            repositoryWithBranches()
            val app = launchWithProject(project, agentPreferences())
            openPicker(app)
            awaitTag(AgentTags.agentBase("other")).performClick()
            awaitTag(AgentTags.AGENT_PROMPT)
            val conversation = checkNotNull(app.project.agent.current)
            assertEquals(WorkMode.Worktree, conversation.mode)
            awaitCondition { workspaceFile(conversation, "only-on-other.txt") }
            assertFalse(File(project.root.toFile(), "only-on-other.txt").exists(), "the project itself is untouched")
        }

    @Test
    fun `a remote branch is the base of the worktree of the new conversation`() =
        runComposeUiTest {
            repositoryWithBranches()
            val app = launchWithProject(project, agentPreferences())
            openPicker(app)
            awaitTag(AgentTags.agentBase("origin/feature")).performClick()
            awaitTag(AgentTags.AGENT_PROMPT)
            val conversation = checkNotNull(app.project.agent.current)
            assertEquals(WorkMode.Worktree, conversation.mode)
            awaitCondition { workspaceFile(conversation, "only-on-other.txt") }
        }

    @Test
    fun `the project's own branch works in the project, without a worktree`() =
        runComposeUiTest {
            repositoryWithBranches()
            val app = launchWithProject(project, agentPreferences())
            openPicker(app)
            awaitTag(AgentTags.AGENT_BASE_PROJECT).performClick()
            awaitTag(AgentTags.AGENT_PROMPT)
            val conversation = checkNotNull(app.project.agent.current)
            assertEquals(WorkMode.Direct, conversation.mode)
            assertNull(conversation.base)
        }

    @Test
    fun `Escape closes the picker and no conversation starts`() =
        runComposeUiTest {
            repositoryWithBranches()
            val app = launchWithProject(project, agentPreferences())
            openPicker(app)
            awaitTag(AgentTags.AGENT_BASE_SEARCH).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(AgentTags.AGENT_BASE_PICKER)
            assertTrue(app.project.agent.conversations.isEmpty())
            assertNull(app.project.agent.current)
        }

    @Test
    fun `the search narrows the branches and Enter takes the highlighted one`() =
        runComposeUiTest {
            repositoryWithBranches()
            val app = launchWithProject(project, agentPreferences())
            openPicker(app)
            awaitTag(AgentTags.AGENT_BASE_SEARCH).performTextInput("feat")
            awaitNoTag(AgentTags.agentBase("other"))
            // The project's own line stays first: arrow down reaches the branch, Enter takes it.
            awaitTag(AgentTags.AGENT_BASE_SEARCH).performKeyInput {
                pressKey(Key.DirectionDown)
                pressKey(Key.Enter)
            }
            awaitTag(AgentTags.AGENT_PROMPT)
            assertEquals("origin/feature", assertNotNull(app.project.agent.current).base)
        }

    @Test
    fun `a project that is no repository starts its conversation without asking`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            startConversation(app)
            awaitNoTag(AgentTags.AGENT_BASE_PICKER)
            assertEquals(WorkMode.Direct, assertNotNull(app.project.agent.current).mode)
        }

    @Test
    fun `choosing a base leaves the project and its repository as they were but for the agent's branch`() =
        runComposeUiTest {
            repositoryWithBranches()
            val before = DiskSnapshot.take(project)
            val app = launchWithProject(project, agentPreferences())
            openPicker(app)
            awaitTag(AgentTags.agentBase("origin/feature")).performClick()
            val conversation = checkNotNull(app.project.agent.current)
            awaitCondition { workspaceFile(conversation, "only-on-other.txt") }
            before.assertUnchanged(project, exceptRefs = setOf("refs/heads/${conversation.branch}"))
        }
}
