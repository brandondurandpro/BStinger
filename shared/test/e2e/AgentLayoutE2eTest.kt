package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.GitTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Agent mode: the project tree sits under the conversation list, in the same column. */
@OptIn(ExperimentalTestApi::class)
class AgentLayoutE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the project tree is under the conversation list`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            startConversation(app)
            val list = awaitTag(AgentTags.AGENT_LIST).getBoundsInRoot()
            val tree = awaitTag(TreeTags.PROJECT_PANEL).getBoundsInRoot()
            assertTrue(tree.top >= list.bottom, "the tree starts under the list: $tree vs $list")
            assertTrue(tree.left < list.right, "the tree is in the column of the list: $tree vs $list")
        }

    @Test
    fun `a panel of the column gets taller when its splitter is dragged down`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            startConversation(app)
            val before = awaitTag(AgentTags.AGENT_LIST).getBoundsInRoot().let { it.bottom - it.top }
            awaitTag(AgentTags.AGENT_LIST_HEIGHT_SPLITTER).performTouchInput { swipeDown(startY = 0f, endY = 100f) }
            awaitCondition { onNodeWithTag(AgentTags.AGENT_LIST).getBoundsInRoot().let { it.bottom - it.top } > before }
        }

    @Test
    fun `a card folds to its header and unfolds from the chevron of its header`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            startConversation(app)
            val open = awaitTag(AgentTags.AGENT_LIST).getBoundsInRoot().let { it.bottom - it.top }
            awaitTag(AgentTags.AGENT_LIST_BAR).performClick()
            awaitCondition { onNodeWithTag(AgentTags.AGENT_LIST).getBoundsInRoot().let { it.bottom - it.top } < open }
            awaitTag(AgentTags.AGENT_LIST_BAR).performClick()
            awaitCondition { onNodeWithTag(AgentTags.AGENT_LIST).getBoundsInRoot().let { it.bottom - it.top } >= open }
        }

    @Test
    fun `the Git panel is above the project tree`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            startConversation(app)
            app.project.toolWindows.setVisible(ToolWindow.Git, true)
            // Folded, the cards above bring the tree into the window: a card out of view has no bounds.
            awaitTag(AgentTags.AGENT_LIST_BAR).performClick()
            awaitTag(AppTags.dockCardBar(ToolWindow.Git.name)).performClick()
            val git = awaitTag(GitTags.GIT_PANEL).getBoundsInRoot()
            val tree = awaitTag(TreeTags.PROJECT_PANEL).getBoundsInRoot()
            assertTrue(git.bottom <= tree.top, "the Git panel ends above the tree: $git vs $tree")
        }

    @Test
    fun `a click on the title of a card folds it`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            startConversation(app)
            val open = awaitTag(AgentTags.AGENT_LIST).getBoundsInRoot().let { it.bottom - it.top }
            onNodeWithText("Conversations").performClick()
            awaitCondition { onNodeWithTag(AgentTags.AGENT_LIST).getBoundsInRoot().let { it.bottom - it.top } < open }
        }
}
