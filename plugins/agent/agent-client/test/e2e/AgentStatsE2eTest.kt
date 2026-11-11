package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The Statistics link of a conversation opens its figures: prompts, time worked, cost. */
@OptIn(ExperimentalTestApi::class)
class AgentStatsE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the statistics of a conversation count its prompts, its time and its cost`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("echo hello")
            awaitAnswer(conversation, "hello")
            awaitIdle(conversation)
            awaitCondition { conversation.usage != null && conversation.timedTurns >= 1 }
            awaitTag(AgentTags.AGENT_STATS).performClick()
            awaitTag(AgentTags.AGENT_STATS_DIALOG)
            assertEquals("1", textOf(AgentTags.AGENT_STATS_PROMPTS))
            assertContains(textOf(AgentTags.AGENT_STATS_COST), "\$0.01")
            awaitCondition { textOf(AgentTags.AGENT_STATS_TIME).endsWith("s") }
            awaitTag(AgentTags.AGENT_STATS_CLOSE).performClick()
            awaitNoTag(AgentTags.AGENT_STATS_DIALOG)
        }

    @Test
    fun `the statistics link is in the card beside a wide conversation`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            app.project.agent.conversationsVisible = false
            sendPrompt("echo hello")
            awaitShown(conversation, "hello")
            awaitTag(AgentTags.AGENT_QUOTA_CARD)
            awaitTag(AgentTags.AGENT_STATS).performClick()
            awaitTag(AgentTags.AGENT_STATS_DIALOG)
        }
}
