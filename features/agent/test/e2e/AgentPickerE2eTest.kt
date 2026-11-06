package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.acp.registryPlatformForTests
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.testkit.acp.FakeRegistry
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The agent of a conversation, chosen under its prompt among the agents the server can run (its registry): the one of
 * the settings until a choice, the choice of one conversation kept from the others, refused while it answers, and the
 * agents the server cannot run shown why. A choice applies to the next prompt: a new session, what was told stays.
 */
@OptIn(ExperimentalTestApi::class)
class AgentPickerE2eTest {
    private val project = SampleProject.standard()
    private val registry = FakeRegistry(registryPlatformForTests())
    private val data = Files.createTempDirectory("agent-data")

    @AfterTest
    fun cleanup() {
        registry.down = false
        registry.close()
        project.delete()
        data.toFile().deleteRecursively()
    }

    // The agents of a server that reads [registry]: the agent of the settings is a command line, no registry.
    private fun agents() = agentSettings(registry.url, data.toFile())

    @Test
    fun `the agent chosen from the registry answers the next prompt of the conversation`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), agents = agents())
            val conversation = startConversation(app)
            awaitAgent("Settings")
            sendPrompt("count")
            awaitShown(conversation, "Prompt 1 of session-")
            awaitIdle(conversation)
            chooseAgent("fake-agent")
            sendPrompt("count")
            // A new session of the new agent: the conversation says so, and it counts from one again.
            awaitShown(conversation, "no longer remembers what the previous agent")
            awaitShown(conversation, "Prompt 1 of session-")
            awaitIdle(conversation)
            assertEquals(1, registry.downloads.get())
            // Both prompts stay: what was told to the previous agent is not thrown away.
            assertEquals(2, conversation.itemsOf<AgentItem.UserText>().size)
            awaitAgent("Fake Agent")
        }

    @Test
    fun `the agent of the settings comes back and the conversation goes on in a new session`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), agents = agents())
            val conversation = startConversation(app)
            sendPrompt("count")
            awaitShown(conversation, "Prompt 1 of session-")
            awaitIdle(conversation)
            chooseAgent("fake-agent")
            sendPrompt("count")
            awaitShown(conversation, "no longer remembers what the previous agent")
            awaitIdle(conversation)
            // From the menu, the conversation goes back to the agent of the settings.
            awaitTag(AgentTags.AGENT_AGENT).performClick()
            val back = awaitTag(AgentTags.AGENT_AGENT_DEFAULT, timeout = AGENT_TIMEOUT)
            assertContains(textOf(AgentTags.AGENT_AGENT_DEFAULT), "Agent of the settings")
            back.performClick()
            awaitCondition(AGENT_TIMEOUT) { app.project.agent.current?.agent == null }
            awaitNoTag(AgentTags.AGENT_AGENT_DEFAULT)
            sendPrompt("count")
            // The settings' agent got the conversation over: a new session again, counting from one.
            awaitShown(conversation, "no longer remembers what the previous agent")
            awaitIdle(conversation)
        }

    @Test
    fun `each conversation keeps its own agent`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), agents = agents())
            val first = startConversation(app)
            chooseAgent("fake-agent")
            sendPrompt("count")
            // The choice before the first prompt: the agent of the registry answers right away, without a notice.
            awaitShown(first, "Prompt 1 of")
            awaitIdle(first)
            awaitTag(AgentTags.AGENT_NEW).performClick()
            val second = checkNotNull(app.project.agent.current)
            // The new conversation runs the agent of the settings: its choice to show, its own counting.
            assertNull(second.agent)
            awaitAgent("Settings")
            sendPrompt("count")
            awaitShown(second, "Prompt 1 of session-")
            awaitIdle(second)
            // The first conversation kept its choice, and shows the agent it runs.
            awaitTag(AgentTags.agentTab(first.id)).performClick()
            awaitCondition(AGENT_TIMEOUT) { app.project.agent.current?.agent != null }
            awaitAgent("Fake Agent")
        }

    @Test
    fun `the agent does not change while the conversation answers`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), agents = agents())
            val conversation = startConversation(app)
            val file = project.root.resolve("NOTES.md")
            sendPrompt("write $file by the agent")
            // The agent waits for the permission: busy, it keeps its agent until its turn is over.
            awaitTag(AgentTags.agentPermission("reject"), timeout = AGENT_TIMEOUT)
            awaitTag(AgentTags.AGENT_AGENT).performClick()
            awaitTag(AgentTags.agentAgent("fake-agent"), timeout = AGENT_TIMEOUT).performClick()
            waitForIdle()
            assertNull(app.project.agent.current?.agent)
            assertEquals(0, registry.downloads.get())
            onNodeWithTag(AgentTags.agentPermission("reject")).performClick()
            awaitIdle(conversation)
            // Once the turn is over, the choice applies.
            chooseAgent("fake-agent")
            sendPrompt("count")
            awaitShown(conversation, "no longer remembers what the previous agent")
            awaitIdle(conversation)
        }

    @Test
    fun `a filter typed in the menu narrows the agents of the registry`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), agents = agents())
            val conversation = startConversation(app)
            awaitAgent("Settings")
            awaitTag(AgentTags.AGENT_AGENT).performClick()
            awaitTag(AgentTags.agentAgent("unsigned-agent"), timeout = AGENT_TIMEOUT)
            typeFilter("fake")
            // Only the row that the filter names; clicking it chooses the usual way.
            awaitNoTag(AgentTags.agentAgent("unsigned-agent"))
            awaitTag(AgentTags.agentAgent("fake-agent")).performClick()
            awaitNoTag(AgentTags.agentAgent("fake-agent"))
            sendPrompt("count")
            awaitShown(conversation, "Prompt 1 of")
            awaitIdle(conversation)
        }

    @Test
    fun `an agent the server cannot run is shown why and stays unchosen`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), agents = agents())
            startConversation(app)
            awaitAgent("Settings")
            awaitTag(AgentTags.AGENT_AGENT).performClick()
            awaitCondition(AGENT_TIMEOUT) { textOf(AgentTags.agentAgent("unsigned-agent")).contains("no checksum") }
            assertNull(app.project.agent.current?.agent)
        }

    @Test
    fun `a registry the server cannot read says so and the conversation runs`() =
        runComposeUiTest {
            registry.down = true
            val app = launchWithProject(project, agentPreferences(), agents = agents())
            val conversation = startConversation(app)
            awaitAgent("Settings")
            awaitTag(AgentTags.AGENT_AGENT).performClick()
            awaitCondition(AGENT_TIMEOUT) { textOf(AgentTags.AGENT_REGISTRY).contains("could not be read") }
            // The item of the registry out of reach closes the menu: nothing chosen, nothing kept the agent from
            // running.
            awaitTag(AgentTags.AGENT_REGISTRY).performClick()
            awaitNoTag(AgentTags.AGENT_REGISTRY)
            sendPrompt("count")
            awaitShown(conversation, "Prompt 1 of session-")
            awaitIdle(conversation)
        }
}
