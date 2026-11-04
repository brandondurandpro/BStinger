package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mode of permissions of a conversation, chosen under its prompt among those the agent offers (the fake agent,
 * like Claude: Manual asks before it writes, Accept edits writes without asking, Plan): the session keeps it, apart
 * from its model.
 */
@OptIn(ExperimentalTestApi::class)
class AgentPermissionsE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun preferences(vararg options: String) = agentPreferences("--store", "$store", *options)

    @Test
    fun `in Accept edits the agent writes without asking`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            awaitPermissions("Manual")
            choosePermissions("accept-edits")
            awaitPermissions("Accept edits")
            val file = project.root.resolve("ACCEPTED.md")
            sendPrompt("write $file accepted")
            awaitShown(conversation, "Wrote")
            awaitIdle(conversation)
            assertEquals("accepted", file.readText())
            assertTrue(conversation.itemsOf<AgentItem.Permission>().isEmpty(), "Asked: ${conversation.items}")
        }

    @Test
    fun `back in Manual the agent asks again`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            choosePermissions("accept-edits")
            val first = project.root.resolve("FIRST.md")
            sendPrompt("write $first one")
            awaitShown(conversation, "Wrote")
            awaitIdle(conversation)
            choosePermissions("manual")
            awaitPermissions("Manual")
            val second = project.root.resolve("SECOND.md")
            sendPrompt("write $second two")
            answerPermission("allow")
            awaitShown(conversation, "Wrote $second")
            awaitIdle(conversation)
            assertEquals("two", second.readText())
            assertEquals(1, conversation.itemsOf<AgentItem.Permission>().size)
        }

    @Test
    fun `the mode and the model are chosen apart`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            awaitTag(AgentTags.AGENT_MODEL, timeout = AGENT_TIMEOUT)
            choosePermissions("plan")
            chooseModel("fake-smart")
            sendPrompt("mode")
            awaitShown(conversation, "Mode: plan.")
            awaitIdle(conversation)
            sendPrompt("model")
            awaitShown(conversation, "Model: fake-smart.")
            awaitIdle(conversation)
            awaitPermissions("Plan")
            awaitModel("Smart")
        }

    @Test
    fun `an agent without modes shows no permissions button`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences("--no-config"))
            val conversation = startConversation(app)
            sendPrompt("echo no modes")
            awaitShown(conversation, "no modes")
            awaitIdle(conversation)
            assertFalse(isShown(AgentTags.AGENT_PERMISSIONS))
        }
}
