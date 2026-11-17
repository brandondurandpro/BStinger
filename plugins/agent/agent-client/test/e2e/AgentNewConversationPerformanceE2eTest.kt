package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val CONVERSATIONS = 5
private const val BUDGET_MILLIS_EACH = 600L

/**
 * Performance of the + button: the conversation, which works in an empty folder the server makes, opens at once. The
 * folder is empty and no repository, so there is nothing for the window to wait for before showing it.
 */
@OptIn(ExperimentalTestApi::class)
class AgentNewConversationPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `plus opens a conversation without waiting for a tree that is empty`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            startConversation(app)
            measure("$CONVERSATIONS conversations with +", BUDGET_MILLIS_EACH * CONVERSATIONS, budgetMegabytes = 400) {
                repeat(CONVERSATIONS) { clickNewConversationWithoutProject(app) }
                renderFrame()
            }
            assertEquals(CONVERSATIONS + 1, app.project.agent.conversations.size)
        }
}
