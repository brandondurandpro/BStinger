package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolStatus
import java.util.concurrent.ConcurrentHashMap

private const val TITLE = "title"
private const val KIND = "kind"
private const val STATUS = "status"

/**
 * Turns the updates of an agent's session into items of [journal]: chunks of text grow the item they continue, a
 * tool call and its updates are one item, the plan of a prompt is one item, replaced as it goes.
 */
internal class TranscriptWriter(
    private val journal: Journal,
    private val terminals: (String) -> AgentTerminal? = { null },
) {
    // Journal item of each tool call of the current session, by the id the agent gave it.
    private val toolItems = ConcurrentHashMap<String, String>()

    // Journal item of the tool call that shows each terminal, by terminal id.
    private val terminalItems = ConcurrentHashMap<String, String>()

    // Message the latest chunk belonged to, when the agent numbers its messages: a new one starts a new item.
    @Volatile
    private var messageId: String? = null

    /**
     * What becomes of the updates: [Mode.Live] for a prompt, [Mode.Replay] while the agent replays the history of a
     * session (the user's messages too), [Mode.Muted] for a replay of what the journal already shows.
     */
    @Volatile
    var mode = Mode.Live

    /** A new session started: its tool call ids are its own. */
    fun newSession() {
        toolItems.clear()
        terminalItems.clear()
        messageId = null
    }

    /** The output of [terminal] changed: the tool call that shows it shows the new one. */
    fun terminalChanged(terminal: AgentTerminal) {
        val itemId = terminalItems[terminal.id] ?: return
        val shown = terminal.shown()
        journal.update(itemId) { item, version ->
            (item as AgentItem.ToolCall).copy(version = version, terminal = shown)
        }
    }

    /** Writes [update], a `session/update`; the kinds this IDE does not show are ignored. */
    fun write(update: JsonObject) {
        val kind = update.text("sessionUpdate")
        // The settings are the session's as it is now, even while a replay of the history is muted.
        val options = update.configOptions().takeIf { kind == "config_option_update" }
        if (options != null) journal.describe { it.copy(options = options) }
        if (mode == Mode.Muted) return
        when (kind) {
            "user_message_chunk" -> if (mode == Mode.Replay) append(update, Text.User)
            "agent_message_chunk" -> append(update, Text.Agent)
            "agent_thought_chunk" -> append(update, Text.Thought)
            "tool_call", "tool_call_update" -> tool(update)
            "plan" -> plan(update)
            "session_info_update" -> info(update)
        }
    }

    /** Writes the tool call of a permission request, and returns what it asks to do. */
    fun permissionTitle(call: JsonObject?): String {
        call?.let(::tool)
        val callId = call?.text("toolCallId")
        val known = callId?.let(toolItems::get)?.let { journal[it] as? AgentItem.ToolCall }?.title
        return call?.text(TITLE) ?: known ?: "Run a tool"
    }

    /** Ends the tool calls still running, when the prompt ended: [status] says how. */
    fun closeRunningTools(status: AgentToolStatus) {
        val running = journal.filter { item -> (item as? AgentItem.ToolCall)?.takeIf { it.isRunning() } }
        for (call in running) {
            journal.update(call.id) { item, version ->
                (item as AgentItem.ToolCall).copy(version = version, status = status)
            }
        }
    }

    /** A notice of the IDE about the conversation. */
    fun notice(text: String, error: Boolean = false) {
        val id = journal.newId()
        journal.add { AgentItem.Notice(id, it, text, error) }
    }

    // A chunk of text: it grows the text item it continues (same kind, same message), or starts one.
    private fun append(update: JsonObject, kind: Text) {
        val chunk = update["content"].obj()?.contentText().orEmpty()
        if (chunk.isEmpty()) return
        val message = update.text("messageId")
        val sameMessage = message == null || message == messageId
        messageId = message
        val continued = journal.last()?.takeIf { sameMessage && kind.continues(it) }
        if (continued != null) {
            journal.update(continued.id) { item, version -> appended(item, chunk, version) }
            return
        }
        val id = journal.newId()
        journal.add { version -> kind.item(id, version, chunk) }
    }

    // The agent titled the session (`title` absent: unchanged; null: cleared).
    private fun info(update: JsonObject) {
        if (!update.containsKey(TITLE)) return
        val title = update.text(TITLE)
        journal.describe { it.copy(title = title) }
    }

    // A tool call starts or changes: the fields present replace those of the item.
    private fun tool(update: JsonObject) {
        val callId = update.text("toolCallId") ?: return
        val existing = toolItems[callId]
        val id = existing ?: journal.newId()
        val content = update.toolContent()
        val terminal = content?.terminals?.lastOrNull()
        if (terminal != null) terminalItems[terminal] = id
        val shown = terminal?.let(terminals)?.shown()
        if (existing == null) {
            toolItems[callId] = id
            journal.add { version -> newTool(id, version, callId, update).copy(terminal = shown) }
        } else {
            journal.update(existing) { item, version ->
                val changed = changedTool(item as AgentItem.ToolCall, version, update)
                if (shown == null) changed else changed.copy(terminal = shown)
            }
        }
    }

    private fun changedTool(call: AgentItem.ToolCall, version: Long, update: JsonObject): AgentItem.ToolCall {
        val content = update.toolContent()
        return call.copy(
            version = version,
            title = update.text(TITLE) ?: call.title,
            kind = update.text(KIND)?.let(::toolKind) ?: call.kind,
            status = toolStatus(update.text(STATUS)) ?: call.status,
            output = content?.output ?: call.output,
            diffs = content?.diffs ?: call.diffs,
            locations = update.toolLocations() ?: call.locations,
        )
    }

    // The plan of the prompt: the latest plan item is replaced while it is the plan of the current prompt.
    private fun plan(update: JsonObject) {
        val entries = update.planEntries()
        val promptStart = journal.filter { it as? AgentItem.UserText }.lastOrNull()?.version ?: 0L
        val current = journal.filter { it as? AgentItem.Plan }.lastOrNull()?.takeIf { it.version > promptStart }
        if (current != null) {
            journal.update(current.id) { item, version ->
                (item as AgentItem.Plan).copy(version = version, entries = entries)
            }
        } else {
            val id = journal.newId()
            journal.add { AgentItem.Plan(id, it, entries) }
        }
    }
}

/** What a transcript writer does with the updates of its session. */
internal enum class Mode { Live, Replay, Muted }

// The kinds of text the agent streams in chunks.
private enum class Text {
    User,
    Agent,
    Thought,
    ;

    fun continues(item: AgentItem): Boolean =
        when (this) {
            User -> item is AgentItem.UserText
            Agent -> item is AgentItem.AgentText
            Thought -> item is AgentItem.Thought
        }

    fun item(id: String, version: Long, text: String): AgentItem =
        when (this) {
            User -> AgentItem.UserText(id, version, text)
            Agent -> AgentItem.AgentText(id, version, text)
            Thought -> AgentItem.Thought(id, version, text)
        }
}

private fun AgentItem.ToolCall.isRunning() = status == AgentToolStatus.Pending || status == AgentToolStatus.Running

private fun appended(item: AgentItem, chunk: String, version: Long): AgentItem =
    when (item) {
        is AgentItem.UserText -> item.copy(version = version, text = item.text + chunk)
        is AgentItem.Thought -> item.copy(version = version, text = item.text + chunk)
        is AgentItem.AgentText -> item.copy(version = version, text = item.text + chunk)
        else -> item
    }

private fun newTool(id: String, version: Long, callId: String, update: JsonObject): AgentItem.ToolCall {
    val content = update.toolContent()
    return AgentItem.ToolCall(
        id = id,
        version = version,
        title = update.text(TITLE) ?: callId,
        kind = toolKind(update.text(KIND)),
        status = toolStatus(update.text(STATUS)) ?: AgentToolStatus.Pending,
        output = content?.output.orEmpty(),
        diffs = content?.diffs.orEmpty(),
        locations = update.toolLocations().orEmpty(),
    )
}
