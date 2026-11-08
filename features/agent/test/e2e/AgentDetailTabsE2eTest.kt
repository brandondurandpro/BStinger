package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Under the conversations, a second row of tabs holds what belongs to the conversation shown: its files and diffs. */
@OptIn(ExperimentalTestApi::class)
class AgentDetailTabsE2eTest {
    private val project = SampleProject.standard()
    private val app = "src/main/kotlin/App.kt"

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `a file opened in the conversation gets a tab in a second row, with a tab back to the conversation`() =
        runComposeUiTest {
            val launched = launchWithProject(project, agentPreferences())
            val conversation = startConversation(launched)
            assertTrue(!isShown(AgentTags.AGENT_DETAIL_TABS), "no second row while nothing is open")
            if (!isShown(TreeTags.treeRow("src"))) awaitTag(AppTags.TOOL_PROJECT).performClick()
            awaitTag(TreeTags.treeChevron("src")).performClick()
            awaitTag(TreeTags.treeChevron("src/main")).performClick()
            awaitTag(TreeTags.treeChevron("src/main/kotlin")).performClick()
            awaitTag(TreeTags.treeRow(app)).performDoubleClick()
            awaitCondition { !conversation.showsChat }
            awaitTag(AgentTags.AGENT_DETAIL_TABS)
            awaitTag(AgentTags.agentDetailChat(conversation.id)).performClick()
            awaitCondition { conversation.showsChat }
            assertEquals(conversation, launched.project.agent.current)
            assertTrue(isShown(AgentTags.AGENT_DETAIL_TABS), "the files stay in the second row")
        }
}
