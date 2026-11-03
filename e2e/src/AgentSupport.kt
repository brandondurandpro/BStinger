package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import org.example.kotlineditor.acp.AgentSettings
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.PreferenceStore
import org.example.kotlineditor.testkit.acp.FakeAgentCommand
import org.example.kotlineditor.testkit.acp.FakeSessions
import org.example.kotlineditor.testkit.acp.FakeTurn
import org.example.kotlineditor.ui.AppTags

/** How long the fake agent may take to start (a JVM) and answer, on a slow runner. */
const val AGENT_TIMEOUT = 60_000L

/** The command line of the fake ACP agent with [options], quoted as typed in the settings. */
fun fakeAgentLine(vararg options: String): String = FakeAgentCommand.command(*options).joinToString(" ") { "\"$it\"" }

/** Preferences with agent mode on and the fake agent (with [options]) as the agent. */
fun agentPreferences(vararg options: String) =
    InMemoryPreferenceStore(
        mapOf(
            PreferenceKeys.AGENT_MODE to "true",
            PreferenceKeys.AGENT_COMMAND to fakeAgentLine(*options),
        ),
    )

/** The agent of the tests that choose none: a program that does not exist, never the real agent (npx). */
const val NO_AGENT = "kotlin-editor-test-no-agent"

/**
 * [preferences], with [NO_AGENT] as the agent when they choose none: agent mode lists the sessions of the agent as it
 * shows, and a test must not start (or download) the agent of the user.
 */
fun withoutRealAgent(preferences: PreferenceStore): PreferenceStore =
    object : PreferenceStore {
        override fun load(): Map<String, String> {
            val values = preferences.load()
            val command = values.containsKey(PreferenceKeys.AGENT_COMMAND)
            val chosen = command || values.containsKey(PreferenceKeys.AGENT_REGISTRY)
            return if (chosen) values else values + (PreferenceKeys.AGENT_COMMAND to NO_AGENT)
        }

        override fun save(values: Map<String, String>) = preferences.save(values)
    }

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

/**
 * Writes a session of the fake agent into [store], as an earlier IDE left it: it works in [directory] and holds
 * [turns], prompts and answers. Returns its id.
 */
fun pastSession(store: java.nio.file.Path, directory: String, turns: List<Pair<String, String>>): String {
    val sessions = FakeSessions(store.toFile())
    val session = sessions.create(directory)
    session.title = turns.firstOrNull()?.first
    turns.forEach { (prompt, reply) -> session.turns.add(FakeTurn(prompt, StringBuilder(reply))) }
    sessions.save(session)
    return session.id
}

/** Switches the open project to agent mode, and waits until the history of the agent lists [session]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitHistory(app: E2eApp, session: String): Conversation {
    awaitTag(AppTags.MODE_AGENT).performClick()
    val conversations = app.project.agent.conversations
    awaitCondition(AGENT_TIMEOUT) { conversations.any { it.session == session } }
    return checkNotNull(conversations.firstOrNull { it.session == session })
}

/** The agents of a test server: [registry] as the ACP registry, installed into [data]. */
fun agentSettings(registry: String, data: java.io.File): AgentSettings =
    AgentSettings(registryUrl = registry, toolsDirectory = data.toPath().resolve("tools"))

/** The agents of a test server that chose no registry: one on a closed port, so that nothing reaches the internet. */
fun offlineAgents(data: java.io.File): AgentSettings = agentSettings("http://127.0.0.1:9/registry.json", data)
