package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.acp.AgentSettings
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.PreferenceStore
import org.example.kotlineditor.testkit.acp.FakeAgentCommand
import org.example.kotlineditor.testkit.acp.FakeSessions
import org.example.kotlineditor.testkit.acp.FakeTurn
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.EditorTags

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
    awaitTag(AgentTags.MODE_AGENT).performClick()
    awaitTag(AgentTags.AGENT_NEW).performClick()
    awaitTag(AgentTags.AGENT_PROMPT)
    return checkNotNull(app.project.agent.current) { "No conversation open" }
}

/** Types [text] in the prompt and sends it with Enter. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.sendPrompt(text: String) {
    val prompt = awaitTag(AgentTags.AGENT_PROMPT)
    prompt.performTextInput(text)
    prompt.performKeyInput { pressKey(Key.Enter) }
}

/** The text the conversation shows, every item and the prompt bar. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.shownConversation(conversation: Conversation): String = textOf(AgentTags.agentView(conversation.id))

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
    awaitTag(AgentTags.AGENT_SEND)
}

/** The items of [conversation] of type [T]. */
inline fun <reified T : AgentItem> Conversation.itemsOf(): List<T> = items.filterIsInstance<T>()

/** Clicks the option [option] of the pending permission request, once it shows. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.answerPermission(option: String) {
    awaitTag(AgentTags.agentPermission(option), timeout = AGENT_TIMEOUT).performClick()
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
    awaitTag(AgentTags.MODE_AGENT).performClick()
    val conversations = app.project.agent.conversations
    awaitCondition(AGENT_TIMEOUT) { conversations.any { it.session == session } }
    return checkNotNull(conversations.firstOrNull { it.session == session })
}

/** The agents of a test server: [registry] as the ACP registry, installed into [data]. */
fun agentSettings(registry: String, data: java.io.File): AgentSettings =
    AgentSettings(registryUrl = registry, toolsDirectory = data.toPath().resolve("tools"))

/** The agents of a test server that chose no registry: one on a closed port, so that nothing reaches the internet. */
fun offlineAgents(data: java.io.File): AgentSettings = agentSettings("http://127.0.0.1:9/registry.json", data)

/** The value a setting button under the prompt shows ([AgentTags.AGENT_MODEL]…), as it reads (`Smart ▾`). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.shownOption(tag: String): String = textOf(tag)

/** Waits until the setting button [tag] shows [name]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitOption(tag: String, name: String, timeout: Long = AGENT_TIMEOUT) {
    awaitTag(tag, timeout = timeout)
    try {
        awaitCondition(timeout) { shownOption(tag) == "$name ▾" }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("'$name' not shown; the button $tag shows '${shownOption(tag)}'", e)
    }
}

/** Chooses, in the menu of the setting button [tag], the value whose item is tagged [item]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.chooseOption(tag: String, item: String) {
    awaitTag(tag, timeout = AGENT_TIMEOUT).performClick()
    awaitTag(item).performClick()
    awaitNoTag(item)
}

/** Waits until the model button shows [name]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitModel(name: String) = awaitOption(AgentTags.AGENT_MODEL, name)

/** Chooses the model [value] in the menu of the model button. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.chooseModel(value: String) = chooseOption(AgentTags.AGENT_MODEL, AgentTags.agentModel(value))

/** Waits until the effort button shows [name]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitEffort(name: String) = awaitOption(AgentTags.AGENT_EFFORT, name)

/** Chooses the effort [value] (`high`). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.chooseEffort(value: String) = chooseOption(AgentTags.AGENT_EFFORT, AgentTags.agentEffort(value))

/** Waits until the button of the mode of permissions shows [name]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitPermissions(name: String) = awaitOption(AgentTags.AGENT_PERMISSIONS, name)

/** Chooses the mode of permissions [value] (`accept-edits`). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.choosePermissions(value: String) =
    chooseOption(AgentTags.AGENT_PERMISSIONS, AgentTags.agentPermissionMode(value))

/** Types [text] in the prompt, without sending it. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.typePrompt(text: String) {
    awaitTag(AgentTags.AGENT_PROMPT).performTextInput(text)
}

/** Presses [key] in the prompt. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.pressInPrompt(key: Key) {
    awaitTag(AgentTags.AGENT_PROMPT).performKeyInput { pressKey(key) }
}

/** The labels of the suggestions of the completion of the prompt, in order. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.suggestions(): List<String> {
    val prefix = AgentTags.agentCompletion("")
    val nodes = onAllNodes(hasTestTagPrefix(prefix)).fetchSemanticsNodes()
    val tags = nodes.mapNotNull { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } }
    return tags.map { it.removePrefix(prefix) }
}

/** Ctrl+V in the prompt: pastes what the in-memory clipboard holds. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.pasteInPrompt() {
    awaitTag(AgentTags.AGENT_PROMPT).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
}

/** Ctrl+Alt+K in the editor: sends its selection (or its file) to the prompt of the agent. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.sendSelectionToAgent() {
    onNodeWithTag(EditorTags.EDITOR).performKeyInput {
        withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.K) } }
    }
}

/** The labels of what is joined to the prompt, in order. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.attachments(): List<String> {
    val prefix = AgentTags.agentAttachment("")
    val nodes = onAllNodes(hasTestTagPrefix(prefix)).fetchSemanticsNodes()
    val tags = nodes.mapNotNull { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } }
    return tags.map { it.removePrefix(prefix) }
}

/** A PNG of one pixel, as a screenshot copied. */
val ONE_PIXEL_PNG: ByteArray =
    java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    )
