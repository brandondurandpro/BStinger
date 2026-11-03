package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.testkit.acp.FakeAgentCommand
import org.example.kotlineditor.ui.AppTags

/** How long the fake agent may take to start (a JVM) and answer, on a slow runner. */
const val AGENT_TIMEOUT = 60_000L

/** The command line of the fake ACP agent with [options], quoted as typed in the settings. */
fun fakeAgentLine(vararg options: String): String =
    FakeAgentCommand.command(*options).joinToString(" ") { "\"$it\"" }

/** Preferences with agent mode on and the fake agent (with [options]) as the agent. */
fun agentPreferences(vararg options: String) =
    InMemoryPreferenceStore(
        mapOf(
            PreferenceKeys.AGENT_MODE to "true",
            PreferenceKeys.AGENT_COMMAND to fakeAgentLine(*options),
        ),
    )

/** Switches the open project to agent mode and starts a conversation; returns it. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.startConversation(app: E2eApp): Conversation {
    awaitTag(AppTags.MODE_AGENT).performClick()
    awaitTag(AppTags.AGENT_NEW).performClick()
    awaitTag(AppTags.AGENT_PROMPT)
    return checkNotNull(app.project.agent.current) { "No conversation open" }
}

/** Types [text] in the prompt and sends it with Enter. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.sendPrompt(text: String) {
    val prompt = awaitTag(AppTags.AGENT_PROMPT)
    prompt.performTextInput(text)
    prompt.performKeyInput { pressKey(Key.Enter) }
}

/** The text the conversation shows, every item and the prompt bar. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.shownConversation(conversation: Conversation): String = textOf(AppTags.agentView(conversation.id))

/** Waits until the conversation shows [text]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitShown(conversation: Conversation, text: String, timeout: Long = AGENT_TIMEOUT) {
    try {
        awaitCondition(timeout) { text in shownConversation(conversation) }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("'$text' not shown; the conversation shows:\n${shownConversation(conversation)}", e)
    }
}

/** Waits until the agent of [conversation] is done and the Send button is back. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitIdle(conversation: Conversation, timeout: Long = AGENT_TIMEOUT) {
    awaitCondition(timeout) { !conversation.busy }
    awaitTag(AppTags.AGENT_SEND)
}

/** The items of [conversation] of type [T]. */
inline fun <reified T : AgentItem> Conversation.itemsOf(): List<T> = items.filterIsInstance<T>()

/** Clicks the option [option] of the pending permission request, once it shows. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.answerPermission(option: String) {
    awaitTag(AppTags.agentPermission(option), timeout = AGENT_TIMEOUT).performClick()
}

/** Leaves the node tagged [tag] alone: used to check a button is there without clicking it. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.isShown(tag: String): Boolean = runCatching { onNodeWithTag(tag).assertExists() }.isSuccess
