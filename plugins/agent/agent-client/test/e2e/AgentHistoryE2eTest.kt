package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.AppTags
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The history of agent mode, which the agent keeps (the fake agent in a directory, as Claude Code in its own): its
 * sessions of the project are listed, open with their messages and go on, and a deleted one is deleted in the agent.
 * The IDE and the server store none of it.
 */
@OptIn(ExperimentalTestApi::class)
class AgentHistoryE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    // The text of a message, null for the other items.
    private fun textOf(item: AgentItem): String? =
        when (item) {
            is AgentItem.UserText -> item.text
            is AgentItem.AgentText -> item.text
            else -> null
        }

    private fun preferences(vararg options: String) = agentPreferences("--store", "$store", *options)

    @Test
    fun `a session of the agent is listed, opens with its messages and goes on`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val turns = listOf("echo hello history" to "hello history ", "echo once more" to "once more ")
            val session = pastSession(store, app.project.root.path, turns)
            val conversation = awaitHistory(app, session)
            assertEquals("echo hello history", conversation.title)
            awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
            awaitShown(conversation, "once more")
            val shown = conversation.items.map(::textOf)
            assertEquals(listOf("echo hello history", "hello history ", "echo once more", "once more "), shown)
            awaitIdle(conversation)
            sendPrompt("count")
            // The agent reopened the session: it remembers the two prompts before.
            awaitShown(conversation, "Prompt 3 of $session.")
            awaitIdle(conversation)
            assertEquals(3, conversation.itemsOf<AgentItem.UserText>().size)
        }

    // Shows agent mode again, which lists the sessions of the agent.
    private fun ComposeUiTest.listHistoryAgain() {
        awaitTag(AgentTags.MODE_IDE).performClick()
        awaitTag(AgentTags.MODE_AGENT).performClick()
    }

    @Test
    fun `a session the agent lists before the IDE knows it shows once, one made to read the settings never`() =
        runComposeUiTest {
            // The agent answers `session/new` only once it listed the new session.
            val app = launchWithProject(project, preferences("--new-after-list"))
            val conversation = startConversation(app)
            val conversations = app.project.agent.conversations
            // First the session that reads the settings of the new conversation, deleted at once; the prompt is sent
            // once it exists, or the session of the conversation may be made before it and take its number.
            awaitCondition(AGENT_TIMEOUT) { store.resolve("session-1.json").exists() }
            sendPrompt("echo only once")
            listHistoryAgain()
            awaitCondition(AGENT_TIMEOUT) { store.resolve("session-2.json").exists() }
            listHistoryAgain()
            awaitAnswer(conversation, "only once")
            awaitIdle(conversation)
            awaitCondition(AGENT_TIMEOUT) { conversations.size == 1 }
            assertEquals(listOf(conversation), conversations.toList())
            assertEquals("session-2", conversation.session)
        }

    @Test
    fun `deleting a conversation of the history deletes the session in the agent`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val session = pastSession(store, app.project.root.path, listOf("echo bye" to "bye "))
            val conversation = awaitHistory(app, session)
            rightClickOn(AgentTags.agentConversation(conversation.id))
            awaitTag(AgentTags.AGENT_DELETE).performClick()
            awaitCondition(AGENT_TIMEOUT) { !store.resolve("$session.json").exists() }
            val conversations = app.project.agent.conversations
            assertTrue(conversations.none { it.session == session })
        }

    @Test
    fun `an agent without history lists nothing, and a new conversation works`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences("--no-history"))
            pastSession(store, app.project.root.path, listOf("echo hidden" to "hidden "))
            val conversation = startConversation(app)
            sendPrompt("echo visible")
            awaitAnswer(conversation, "visible")
            awaitIdle(conversation)
            val conversations = app.project.agent.conversations
            assertEquals(listOf(conversation), conversations.toList())
        }

    @Test
    fun `a session of another project is not listed`() =
        runComposeUiTest {
            val other = Files.createTempDirectory("other-project").toString()
            val app = launchWithProject(project, preferences())
            pastSession(store, other, listOf("echo elsewhere" to "elsewhere "))
            val mine = pastSession(store, app.project.root.path, listOf("echo here" to "here "))
            awaitHistory(app, mine)
            val conversations = app.project.agent.conversations
            assertEquals(listOf(mine), conversations.mapNotNull { it.session })
        }
}
