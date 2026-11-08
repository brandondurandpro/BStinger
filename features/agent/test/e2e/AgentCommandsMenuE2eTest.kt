package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The `/` button above the prompt lists the commands of the agent, with a field that searches them. */
@OptIn(ExperimentalTestApi::class)
class AgentCommandsMenuE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the commands are searched and the one chosen goes to the prompt`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences("--commands", "20"))
            val conversation = startConversation(app)
            awaitTag(AgentTags.AGENT_COMMANDS, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.AGENT_COMMANDS_FILTER).performTextInput("extra-17")
            awaitNoTag(AgentTags.agentCommand("extra-3"))
            awaitTag(AgentTags.agentCommand("extra-17")).performClick()
            awaitCondition { conversation.draft == "/extra-17 " }
            assertEquals("/extra-17 ", conversation.draft)
        }
}
