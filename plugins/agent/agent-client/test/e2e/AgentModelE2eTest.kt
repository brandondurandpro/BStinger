package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.testkit.acp.FakeSessions
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The model of a conversation, chosen under its prompt among those the agent offers (the fake agent: Fast, Smart,
 * Max): before the first prompt, between two prompts, or by the agent itself; the session keeps it.
 */
@OptIn(ExperimentalTestApi::class)
class AgentModelE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun preferences(vararg options: String) = agentPreferences("--store", "$store", *options)

    @Test
    fun `a model chosen before the first prompt answers it`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            // Offered before the session starts: the agent says which models a session has.
            awaitModel("Fast")
            chooseModel("fake-smart")
            awaitModel("Smart")
            sendPrompt("model")
            awaitShown(conversation, "Model: fake-smart.")
            awaitIdle(conversation)
            awaitModel("Smart")
        }

    @Test
    fun `the model changes between two prompts, and every model is listed`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            sendPrompt("model")
            awaitShown(conversation, "Model: fake-fast.")
            awaitIdle(conversation)
            awaitTag(AgentTags.AGENT_MODEL, timeout = AGENT_TIMEOUT).performClick()
            // The models of every group of the agent, in its order.
            for (value in listOf("fake-fast", "fake-smart", "fake-max")) awaitTag(AgentTags.agentModel(value))
            awaitTag(AgentTags.agentModel("fake-max")).performClick()
            awaitModel("Max")
            sendPrompt("model")
            awaitShown(conversation, "Model: fake-max.")
            awaitIdle(conversation)
        }

    @Test
    fun `a model the agent moves to itself shows`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            chooseModel("fake-smart")
            sendPrompt("fallback")
            awaitShown(conversation, "Fell back to fake-fast.")
            awaitIdle(conversation)
            awaitModel("Fast")
        }

    @Test
    fun `the menu closed without a choice changes nothing`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            awaitTag(AgentTags.AGENT_MODEL, timeout = AGENT_TIMEOUT).performClick()
            val item = awaitTag(AgentTags.agentModel("fake-max"))
            item.requestFocus()
            item.performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(AgentTags.agentModel("fake-max"))
            awaitModel("Fast")
            sendPrompt("model")
            awaitShown(conversation, "Model: fake-fast.")
            awaitIdle(conversation)
        }

    @Test
    fun `an agent without models shows no model button`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences("--no-config"))
            val conversation = startConversation(app)
            sendPrompt("echo no models")
            awaitShown(conversation, "no models")
            awaitIdle(conversation)
            assertFalse(isShown(AgentTags.AGENT_MODEL))
        }

    @Test
    fun `a conversation of the history shows the model its session kept`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val sessions = FakeSessions(store.toFile())
            val past = sessions.create(app.project.root.path)
            past.title = "echo kept"
            past.model = "fake-max"
            sessions.save(past)
            val conversation = awaitHistory(app, past.id)
            awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
            awaitModel("Max")
            sendPrompt("model")
            awaitShown(conversation, "Model: fake-max.")
            awaitIdle(conversation)
            assertEquals(past.id, conversation.session)
        }

    @Test
    fun `each conversation has its own model`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val first = startConversation(app)
            chooseModel("fake-max")
            sendPrompt("model")
            awaitShown(first, "Model: fake-max.")
            awaitIdle(first)
            awaitTag(AgentTags.AGENT_NEW).performClick()
            val second = checkNotNull(app.project.agent.current)
            awaitModel("Fast")
            sendPrompt("model")
            awaitShown(second, "Model: fake-fast.")
            awaitIdle(second)
            awaitTag(AgentTags.agentTab(first.id)).performClick()
            awaitModel("Max")
        }
}
