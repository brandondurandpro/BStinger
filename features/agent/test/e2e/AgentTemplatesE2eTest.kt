package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A prompt saved as a template comes back from the `/` menu, and goes when the user removes it. */
@OptIn(ExperimentalTestApi::class)
class AgentTemplatesE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the prompt is saved as a template, put back in the prompt, then removed`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            conversation.draft = "Explain this file"
            awaitTag(AgentTags.AGENT_COMMANDS, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.AGENT_TEMPLATE_SAVE).performClick()
            conversation.draft = ""
            awaitTag(AgentTags.AGENT_COMMANDS).performClick()
            awaitTag(AgentTags.agentTemplate("Explain this file")).performClick()
            awaitCondition { conversation.draft == "Explain this file" }
            assertEquals("Explain this file", conversation.draft)
            awaitTag(AgentTags.AGENT_COMMANDS).performClick()
            awaitTag(AgentTags.agentTemplateRemove("Explain this file")).performClick()
            awaitCondition { app.project.agent.templates.isEmpty() }
        }
}
