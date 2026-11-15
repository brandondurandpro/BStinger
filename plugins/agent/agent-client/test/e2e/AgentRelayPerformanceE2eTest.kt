package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.AgentTeam
import org.example.kotlineditor.state.OrchestrationMode
import org.example.kotlineditor.state.TeamMember
import org.example.kotlineditor.state.relayBriefing
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val CHILD_ITEMS = 500

/** The popup of a member whose session has hundreds of items, and the briefing of a large team. */
@OptIn(ExperimentalTestApi::class)
class AgentRelayPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    @Test
    fun `the popup of a member with 500 tool calls opens quickly and composes only what is in view`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences("--store", "$store"))
            val conversation = startConversation(app)
            app.project.agent.saveTeam(null, relayTeam())
            awaitTag(AgentTags.AGENT_TEAM, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.agentTeam("Crew")).performClick()
            sendPrompt("hello")
            awaitIdle(conversation)
            sendPrompt("delegate-via-mcp Dev tools $CHILD_ITEMS")
            awaitTag(AgentTags.agentStripEntry("Dev"), timeout = AGENT_TIMEOUT)
            awaitIdle(conversation)
            measure("open the popup of a member with $CHILD_ITEMS tool calls", budgetMillis = 10_000, budgetMegabytes = 600) {
                awaitTag(AgentTags.agentStripEntry("Dev")).performClick()
                awaitTag(AgentTags.AGENT_STRIP_DETAIL)
                awaitCondition(AGENT_TIMEOUT) { onAllNodes(hasText("Search step", substring = true), true).fetchSemanticsNodes().isNotEmpty() }
                renderFrame()
            }
            val composed = onAllNodes(hasText("Search step", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size
            assertTrue(composed < CHILD_ITEMS, "composed cards: $composed of $CHILD_ITEMS")
        }

    @Test
    fun `the briefing of a team of 20 members is written quickly`() {
        val members = (1..20).map { TeamMember("Member$it", "Smart", "high", prompt = "Does job $it.") }
        val team = AgentTeam("Big", TeamMember("Boss", prompt = "Coordinate."), members, OrchestrationMode.Relay)
        val start = System.nanoTime()
        val briefing = relayBriefing(team, "Do it")
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue(millis < 500, "took $millis ms")
        assertTrue(members.all { "Member${it.name.removePrefix("Member")}" in briefing })
    }
}
