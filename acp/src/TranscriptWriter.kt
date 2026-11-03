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
internal class TranscriptWriter(private val journal: Journal) {
    // Journal item of each tool call of the current session, by the id the agent gave it.
    private val toolItems = ConcurrentHashMap<String, String>()

    /** A new session started: its tool call ids are its own. */
    fun newSession() = toolItems.clear()

    /** Writes [update], a `session/update`; the kinds this IDE does not show are ignored. */
    fun write(update: JsonObject) {
        when (update.text("sessionUpdate")) {
            "agent_message_chunk" -> append(update, thought = false)
            "agent_thought_chunk" -> append(update, thought = true)
            "tool_call", "tool_call_update" -> tool(update)
            "plan" -> plan(update)
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

    // A chunk of text: it grows the text item it continues, or starts one.
    private fun append(update: JsonObject, thought: Boolean) {
        val chunk = update["content"].obj()?.contentText().orEmpty()
        val last = journal.last()
        val continued = if (thought) last is AgentItem.Thought else last is AgentItem.AgentText
        if (chunk.isEmpty()) return
        if (last != null && continued) {
            journal.update(last.id) { item, version -> appended(item, chunk, version) }
            return
        }
        val id = journal.newId()
        journal.add { version ->
            if (thought) AgentItem.Thought(id, version, chunk) else AgentItem.AgentText(id, version, chunk)
        }
    }

    private fun appended(item: AgentItem, chunk: String, version: Long): AgentItem =
        when (item) {
            is AgentItem.Thought -> item.copy(version = version, text = item.text + chunk)
            is AgentItem.AgentText -> item.copy(version = version, text = item.text + chunk)
            else -> item
        }

    // A tool call starts or changes: the fields present replace those of the item.
    private fun tool(update: JsonObject) {
        val callId = update.text("toolCallId") ?: return
        val existing = toolItems[callId]
        if (existing == null) {
            val id = journal.newId()
            toolItems[callId] = id
            journal.add { version -> newTool(id, version, callId, update) }
        } else {
            journal.update(existing) { item, version -> changedTool(item as AgentItem.ToolCall, version, update) }
        }
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

private fun AgentItem.ToolCall.isRunning() = status == AgentToolStatus.Pending || status == AgentToolStatus.Running
