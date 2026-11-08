package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/** Once the quota is known, the bars follow it after every turn, and the question asked for it stays out of sight. */
@OptIn(ExperimentalTestApi::class)
class AgentQuotaRefreshE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the quota is read again after a turn without showing the question`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            awaitTag(AgentTags.AGENT_COMMANDS, timeout = AGENT_TIMEOUT)
            sendPrompt("usage")
            awaitCondition(AGENT_TIMEOUT) { conversation.quota.isNotEmpty() }
            awaitIdle(conversation)
            sendPrompt("echo hello")
            awaitShown(conversation, "hello")
            awaitIdle(conversation)
            awaitCondition(AGENT_TIMEOUT) { conversation.quota.firstOrNull()?.percent == THIRD_TURN_PERCENT }
            assertContains(textOf(AgentTags.agentQuotaLimit(0)), "$THIRD_TURN_PERCENT%")
            awaitIdle(conversation)
            val probes = conversation.items.filter { it.id.startsWith("probe-") }
            assertTrue(probes.isEmpty(), "the silent question stays out of the conversation")
        }

    private companion object {
        const val THIRD_TURN_PERCENT = 30
    }
}
