package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

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
        runComposeUiTest(testTimeout = 3.minutes) {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            // The list folded, the conversation is wide enough for the card of the quota.
            app.project.agent.conversationsVisible = false
            awaitTag(AgentTags.AGENT_COMMANDS, timeout = AGENT_TIMEOUT)
            sendPrompt("usage")
            awaitCondition(AGENT_TIMEOUT) { conversation.quota.isNotEmpty() }
            awaitIdle(conversation)
            sendPrompt("echo hello")
            awaitShown(conversation, "hello")
            awaitIdle(conversation)
            // The first answer may be read again too (turn 2): the echo is the third turn, or the fourth with that one.
            awaitCondition(AGENT_TIMEOUT) { (conversation.quota.firstOrNull()?.percent ?: 0) >= THIRD_TURN_PERCENT }
            assertTrue(textOf(AgentTags.agentQuotaLimit(0)).isNotBlank())
            awaitIdle(conversation)
            // Nothing of the silent question was ever shown: the items shown stay those of the user's turns.
            assertTrue(conversation.shownItems.none { it.id.startsWith("probe-") })
            // The limits are in the card beside the conversation, with what the agent said.
            awaitTag(AgentTags.AGENT_QUOTA_CARD)
            val probes = conversation.items.filter { it.id.startsWith("probe-") }
            assertTrue(probes.isEmpty(), "the silent question stays out of the conversation")
        }

    @Test
    fun `the silent question never shows in the conversation, even while it is asked`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("usage")
            awaitCondition(AGENT_TIMEOUT) { conversation.quota.isNotEmpty() }
            awaitIdle(conversation)
            val seen = mutableListOf<String>()
            sendPrompt("echo hello")
            // While the probe is in flight, everything the view shows is the user's own turns.
            awaitCondition(AGENT_TIMEOUT) {
                seen += conversation.shownItems.map { it.id }
                conversation.timedTurns >= 3 && !conversation.busy
            }
            assertTrue(seen.none { it.startsWith("probe-") }, "the question was shown: $seen")
        }

    private companion object {
        const val THIRD_TURN_PERCENT = 30
    }
}
