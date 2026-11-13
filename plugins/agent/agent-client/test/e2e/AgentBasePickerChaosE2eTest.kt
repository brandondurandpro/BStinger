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
    fun `the branches come once the network is back, and the conversation starts once`() =
        runComposeUiTest {
            repositoryWithBranch()
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            awaitTag(AgentTags.MODE_AGENT).performClick()
            app.network.goDown()
            awaitReconnecting()
            awaitTag(AgentTags.AGENT_NEW).performClick()
            assertEquals(0, app.project.agent.conversations.size, "nothing starts while the branches are unknown")
            app.network.comeBack()
            awaitReconnected()
            awaitTag(AgentTags.agentBase("other"), timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.AGENT_PROMPT)
            assertEquals(1, app.project.agent.conversations.size, "one conversation, however many retries")
            assertEquals(WorkMode.Worktree, app.project.agent.conversations.first().mode)
        }

    @Test
    fun `a server that restarts while the picker is open leaves it usable`() =
        runComposeUiTest {
            repositoryWithBranch()
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            awaitTag(AgentTags.MODE_AGENT).performClick()
            awaitTag(AgentTags.AGENT_NEW).performClick()
            awaitTag(AgentTags.agentBase("other"))
            app.process.restart()
            awaitReconnected()
            awaitTag(AgentTags.AGENT_BASE_PROJECT).performClick()
            awaitTag(AgentTags.AGENT_PROMPT)
            assertEquals(1, app.project.agent.conversations.size)
            assertEquals(WorkMode.Direct, app.project.agent.conversations.first().mode)
        }
}
