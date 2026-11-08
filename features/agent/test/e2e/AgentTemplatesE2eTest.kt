package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.PromptTemplate
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ★ button opens the prompt templates: a template is written with a name, kept, used in the prompt, deleted. */
@OptIn(ExperimentalTestApi::class)
class AgentTemplatesE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `a template is created with a name, used in the prompt, renamed then deleted`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            val agent = app.project.agent
            awaitTag(AgentTags.AGENT_TEMPLATES, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.AGENT_TEMPLATE_NAME).performTextInput("Explain")
            awaitTag(AgentTags.AGENT_TEMPLATE_TEXT).performTextInput("Explain this file")
            awaitTag(AgentTags.AGENT_TEMPLATE_SAVE).performClick()
            awaitCondition { agent.templates.toList() == listOf(PromptTemplate("Explain", "Explain this file")) }
            awaitTag(AgentTags.AGENT_TEMPLATE_USE).performClick()
            awaitCondition { conversation.draft == "Explain this file" }
            assertEquals("Explain this file", conversation.draft)
            awaitTag(AgentTags.AGENT_TEMPLATES).performClick()
            awaitTag(AgentTags.agentTemplate("Explain")).performClick()
            awaitTag(AgentTags.AGENT_TEMPLATE_NAME).performTextReplacement("Explain2")
            awaitTag(AgentTags.AGENT_TEMPLATE_PROJECT).performClick()
            awaitTag(AgentTags.AGENT_TEMPLATE_SAVE).performClick()
            awaitCondition { agent.templates.toList() == listOf(PromptTemplate("Explain2", "Explain this file", global = false)) }
            awaitTag(AgentTags.AGENT_TEMPLATE_DELETE).performClick()
            awaitCondition { agent.templates.isEmpty() }
        }

    @Test
    fun `the variables of a template are replaced when it is used`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            awaitTag(AgentTags.AGENT_TEMPLATES, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.AGENT_TEMPLATE_TEXT).performTextInput("on {unknown} ")
            awaitTag(AgentTags.agentTemplateVariable("project")).performClick()
            awaitTag(AgentTags.AGENT_TEMPLATE_USE).performClick()
            awaitCondition { conversation.draft.isNotEmpty() }
            val folder = app.project.root.name
            assertEquals("on {unknown} $folder", conversation.draft)
        }
}
