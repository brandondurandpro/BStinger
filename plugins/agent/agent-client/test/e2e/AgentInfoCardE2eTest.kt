package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.GitAction
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.refreshChanges
import org.example.kotlineditor.ui.AgentTags
import java.io.File
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The "Informations" card: where the agent works, where it started from, the state of its directory in Git. */
@OptIn(ExperimentalTestApi::class)
class AgentInfoCardE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.shows(text: String): Boolean {
        val matcher = hasText(text, substring = true)
        return onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun repository() {
        project.commitAll()
        project.git("branch", "other")
    }

    // A conversation in a worktree started from `other`, the card open on a wide window.
    private fun ComposeUiTest.worktreeConversation(app: E2eApp): Conversation {
        awaitTag(AgentTags.MODE_AGENT).performClick()
        awaitTag(AgentTags.AGENT_NEW).performClick()
        awaitTag(AgentTags.agentBase("other")).performClick()
        awaitTag(AgentTags.AGENT_PROMPT)
        app.project.agent.conversationsVisible = false
        val conversation = checkNotNull(app.project.agent.current)
        awaitCondition { conversation.workspace != null && !conversation.preparingWorktree }
        return conversation
    }

    private fun Conversation.directory(): Path = Path.of(checkNotNull(workspace).root.path)

    @Test
    fun `a worktree shows its branch, where it started from and that it is clean`() =
        runComposeUiTest {
            repository()
            val app = launchWithProject(project, agentPreferences())
            val conversation = worktreeConversation(app)
            awaitTag(AgentTags.AGENT_INFO_MODE, unmerged = true)
            awaitCondition { shows("own worktree") && shows(conversation.branch) }
            awaitCondition { shows("Started from") && shows("other") }
            awaitCondition { shows("up to date") && shows("clean") }
        }

    @Test
    fun `the project itself shows no starting branch`() =
        runComposeUiTest {
            repository()
            val app = launchWithProject(project, agentPreferences())
            startConversation(app)
            app.project.agent.conversationsVisible = false
            awaitCondition { shows("in the project") }
            awaitCondition { shows("clean") }
            assertTrue(!shows("Started from"), "the project has no starting branch")
        }

    @Test
    fun `the card follows the uncommitted files, the commits of the worktree and the progress of its base`() =
        runComposeUiTest {
            repository()
            val app = launchWithProject(project, agentPreferences())
            val conversation = worktreeConversation(app)
            val directory = conversation.directory()
            File(directory.toFile(), "new.txt").writeText("new\n")
            app.project.agent.refreshChanges(conversation)
            awaitCondition { shows("1 uncommitted file") }
            gitIn(directory, "add", "-A")
            gitIn(directory, "commit", "-q", "-m", "work of the agent")
            app.project.agent.refreshChanges(conversation)
            awaitCondition { shows("1 ahead") && shows("clean") && shows("work of the agent") }
            // Another commit on the branch it started from: the worktree is behind.
            project.git("checkout", "-q", "other")
            File(project.root.toFile(), "elsewhere.txt").writeText("x\n")
            project.git("add", "-A")
            project.git("commit", "-q", "-m", "elsewhere")
            project.git("checkout", "-q", "-")
            app.project.agent.refreshChanges(conversation)
            awaitCondition { shows("1 ahead, 1 behind") }
        }

    @Test
    fun `each way to commit and push asks the agent, and keeps what was typed in the prompt`() =
        runComposeUiTest {
            repository()
            val app = launchWithProject(project, agentPreferences())
            val conversation = worktreeConversation(app)
            conversation.draft = "something being typed"
            for (action in GitAction.entries) {
                awaitCondition { !conversation.busy }
                awaitTag(AgentTags.AGENT_GIT_ACTIONS).performClick()
                awaitTag(AgentTags.agentGitAction(action.name)).performClick()
                awaitCondition { conversation.itemsOf<AgentItem.UserText>().any { it.text == action.prompt } }
            }
            assertEquals("something being typed", conversation.draft)
        }

    @Test
    fun `the buttons of the folder show its files and a terminal`() =
        runComposeUiTest {
            repository()
            val app = launchWithProject(project, agentPreferences())
            worktreeConversation(app)
            app.project.toolWindows.setVisible(ToolWindow.Project, false)
            awaitTag(AgentTags.AGENT_INFO_FILES).performClick()
            awaitCondition { app.project.toolWindows.isVisible(ToolWindow.Project) }
            awaitTag(AgentTags.AGENT_INFO_TERMINAL).performClick()
            awaitCondition { app.project.toolWindows.isVisible(ToolWindow.Terminal) }
        }

    @Test
    fun `reading the card changes nothing in the project or its repository but the branch of the worktree`() =
        runComposeUiTest {
            repository()
            val before = DiskSnapshot.take(project)
            val app = launchWithProject(project, agentPreferences())
            val conversation = worktreeConversation(app)
            awaitCondition { shows("up to date") }
            before.assertUnchanged(project, exceptRefs = setOf("refs/heads/${conversation.branch}"))
        }
}
