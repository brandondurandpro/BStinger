package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.acp.registryPlatformForTests
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.testkit.acp.FakeRegistry
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the IDE remembers of the agents: a conversation run by an agent of the registry comes back with this agent
 * after the IDE restarted — its history lists it directly, and its prompt goes on in the session the agent keeps.
 */
@OptIn(ExperimentalTestApi::class)
class AgentMemoryE2eTest {
    private val project = SampleProject.standard()

    // The sessions the registry agent keeps, as an earlier IDE left them (its agent runs with a store).
    private val pastSessions = Files.createTempDirectory("agent-registry-sessions")
    private val registry = FakeRegistry(registryPlatformForTests(), agentOptions = listOf("--store", "$pastSessions"))
    private val data = Files.createTempDirectory("agent-data")
    private val agents = agentSettings(registry.url, data.toFile())

    // The preferences the two IDEs of the test share, as two runs of the IDE do: what the first ran, the second takes.
    private val preferences =
        InMemoryPreferenceStore(
            mapOf(
                PreferenceKeys.AGENT_MODE to "true",
                PreferenceKeys.AGENT_COMMAND to fakeAgentLine(),
            ),
        )

    @AfterTest
    fun cleanup() {
        project.delete()
        registry.close()
        data.toFile().deleteRecursively()
        pastSessions.toFile().deleteRecursively()
    }

    @Test
    fun `a conversation of a registry agent comes back with its agent after the restart of the IDE`() =
        runComposeUiTest {
            val first = launchWithProject(project, withoutRealAgent(preferences), agents = agents)
            val conversation = startConversation(first)
            chooseAgent("fake-agent")
            sendPrompt("count")
            awaitShown(conversation, "Prompt 1 of")
            awaitIdle(conversation)
            val session = checkNotNull(conversation.session) { "The session was never started" }
            // The first IDE left; a second one shows the same project, from the same preferences.
            val second = launchWithProject(project, withoutRealAgent(preferences), agents = agents)
            awaitTag(AgentTags.MODE_AGENT).performClick()
            val conversations = second.project.agent.conversations
            awaitCondition(AGENT_TIMEOUT) { conversations.any { it.session == session } }
            val reopened = checkNotNull(conversations.firstOrNull { it.session == session })
            awaitTag(AgentTags.agentConversation(reopened.id)).performClick()
            // The conversation came back on the agent it ran with: the picker says it, and the prompt goes there.
            awaitAgent("Fake Agent")
            assertEquals("fake-agent", reopened.agent?.registry)
            sendPrompt("echo from the registry agent")
            awaitShown(reopened, "from the registry agent")
            awaitIdle(reopened)
        }
}
