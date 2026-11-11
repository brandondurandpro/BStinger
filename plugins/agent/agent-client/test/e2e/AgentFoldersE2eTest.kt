package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.WorkMode
import org.example.kotlineditor.state.groupByFolder
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The list of conversations in two levels: the project, then the folder each conversation works in. */
@OptIn(ExperimentalTestApi::class)
class AgentFoldersE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the conversations are grouped by project and folder, and a group folds`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project, agentPreferences())
            val first = startConversation(app)
            awaitTag(AgentTags.AGENT_PROJECT_GROUP)
            awaitTag(AgentTags.agentConversation(first.id))
            // A conversation in a worktree of its own is another folder, under the same project.
            val second = app.project.agent.newConversation()
            awaitTag(AgentTags.AGENT_MODE_WORKTREE).performClick()
            // Until its worktree is made, the conversation still shows the project.
            awaitCondition(AGENT_TIMEOUT) {
                val workspace = second.workspace
                second.mode == WorkMode.Worktree && !second.preparingWorktree && workspace != null && workspace !== app.project
            }
            val groups = app.project.agent.groupByFolder(app.project.agent.conversations.toList())
            assertEquals(2, groups.size, "the project folder and the worktree: ${groups.map { it.label }}")
            assertTrue(groups.first().label.startsWith("Project folder"), "the project itself comes first")
            awaitTag(AgentTags.agentConversation(first.id))
            awaitTag(AgentTags.agentConversation(second.id))
            // Folding the project hides all its conversations; unfolding brings them back.
            awaitTag(AgentTags.AGENT_PROJECT_GROUP).performClick()
            awaitNoTag(AgentTags.agentConversation(first.id))
            awaitNoTag(AgentTags.agentConversation(second.id))
            awaitTag(AgentTags.AGENT_PROJECT_GROUP).performClick()
            awaitTag(AgentTags.agentConversation(first.id))
        }
}
