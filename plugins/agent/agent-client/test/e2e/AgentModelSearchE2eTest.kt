package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test

/** A long list of models has a field that searches it. */
@OptIn(ExperimentalTestApi::class)
class AgentModelSearchE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `typing in the model menu keeps the models that match and a click chooses one`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences("--models", "30"))
            startConversation(app)
            awaitTag(AgentTags.AGENT_MODEL, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.AGENT_MODEL + ".filter").performTextInput("Extra 17")
            awaitNoTag(AgentTags.agentModel("fake-extra-3"))
            awaitTag(AgentTags.agentModel("fake-extra-17")).performClick()
            awaitModel("Extra 17")
        }
}
