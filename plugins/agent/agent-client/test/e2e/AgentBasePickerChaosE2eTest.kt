package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.WorkMode
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The question "from which branch?" when the link to the server fails. */
@OptIn(ExperimentalTestApi::class)
class AgentBasePickerChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun repositoryWithBranch() {
        project.commitAll()
        project.git("branch", "other")
    }

    @Test
    fun `the conversation starts once the network is back, and the branches come after the project is chosen`() =
        runComposeUiTest {
            repositoryWithBranch()
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            awaitTag(AgentTags.MODE_AGENT).performClick()
            app.network.goDown()
            awaitReconnecting()
            awaitTag(AgentTags.AGENT_NEW).performClick()
            assertEquals(0, app.project.agent.conversations.size, "nothing starts while the server is out of reach")
            app.network.comeBack()
            awaitReconnected()
            awaitCondition(AGENT_TIMEOUT) { app.project.agent.conversations.size == 1 }
            openFolderMenu()
            awaitTag(AgentTags.AGENT_FOLDER_THIS_PROJECT).performClick()
            awaitTag(AgentTags.agentBase("other"), timeout = AGENT_TIMEOUT).performClick()
            awaitNoTag(AgentTags.AGENT_BASE_PICKER)
            assertEquals(1, app.project.agent.conversations.size, "one conversation, however many retries")
            assertEquals(WorkMode.Worktree, app.project.agent.conversations.first().mode)
        }

    @Test
    fun `a server that restarts while the picker is open leaves it usable`() =
        runComposeUiTest {
            repositoryWithBranch()
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            awaitTag(AgentTags.MODE_AGENT).performClick()
            askBranchOfProject(app)
            awaitTag(AgentTags.agentBase("other"))
            app.process.restart()
            awaitReconnected()
            awaitTag(AgentTags.AGENT_BASE_PROJECT).performClick()
            awaitNoTag(AgentTags.AGENT_BASE_PICKER)
            assertEquals(1, app.project.agent.conversations.size)
            assertEquals(WorkMode.Direct, app.project.agent.conversations.first().mode)
        }
}
