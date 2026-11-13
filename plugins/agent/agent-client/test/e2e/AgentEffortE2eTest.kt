package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The effort of a conversation, chosen under its prompt among those its model offers (the fake agent, like Claude:
 * none for Fast, up to High for Smart, up to Max for Max): it shows with the model, before the first prompt too, and
 * follows a change of model.
 */
@OptIn(ExperimentalTestApi::class)
class AgentEffortE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun preferences() = agentPreferences("--store", "$store")

    @Test
    fun `a model chosen before the first prompt shows its efforts, and the effort chosen answers it`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            awaitModel("Fast")
            // Fast thinks as it does: no effort to choose.
            assertFalse(isShown(AgentTags.AGENT_EFFORT))
            chooseModel("fake-smart")
            awaitEffort("Default")
            chooseEffort("high")
            awaitEffort("High")
            sendPrompt("effort")
            awaitShown(conversation, "Effort: high.")
            awaitIdle(conversation)
            awaitEffort("High")
        }

    @Test
    fun `the efforts follow the model`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            sendPrompt("echo first")
            awaitIdle(conversation)
            chooseModel("fake-max")
            awaitEffort("Default")
            chooseEffort("max")
            awaitEffort("Max")
            chooseModel("fake-smart")
            // Smart has no Max: the agent goes back to its default effort, and the menu lists Smart's.
            awaitEffort("Default")
            awaitTag(AgentTags.AGENT_EFFORT).performClick()
            awaitTag(AgentTags.agentEffort("high"))
            assertFalse(isShown(AgentTags.agentEffort("max")))
            awaitTag(AgentTags.agentEffort("default")).performClick()
            chooseModel("fake-fast")
            awaitNoTag(AgentTags.AGENT_EFFORT, timeout = AGENT_TIMEOUT)
            sendPrompt("effort")
            awaitShown(conversation, "Effort: default.")
            awaitIdle(conversation)
        }

    @Test
    fun `each conversation has its own effort`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val first = startConversation(app)
            chooseModel("fake-smart")
            chooseEffort("low")
            sendPrompt("effort")
            awaitShown(first, "Effort: low.")
            awaitIdle(first)
            startAnotherConversation(app)
            awaitModel("Fast")
            assertFalse(isShown(AgentTags.AGENT_EFFORT))
            awaitTag(AgentTags.agentTab(first.id)).performClick()
            awaitEffort("Low")
        }
}
