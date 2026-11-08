package org.example.kotlineditor.acp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentSlashCommand
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.model.AgentUsage

/**
 * The transcript of one conversation, as the server keeps it: items in the order they started, each one stamped with
 * the version of its last change. A client that has version `n` asks for the items newer than `n`, so a reconnection
 * loses nothing and repeats nothing, however many chunks the agent streamed meanwhile. Thread-safe.
 */
internal class Journal(private val epoch: String) {
    private val lock = Any()
    private val items = ArrayList<AgentItem>()
    private val positions = HashMap<String, Int>()
    private var created = 0L
    private val changes = MutableStateFlow(0L)

    /** Version of the latest change: it grows with every item added or changed, and with every state change. */
    val version: StateFlow<Long> get() = changes

    /** What the transcript says besides its items: the state of the agent, its name, its session. */
    var header: Header = Header()
        private set

    val state: AgentTurnState get() = header.state

    /** A new id, unique across restarts of the server thanks to its epoch. */
    fun newId(): String = synchronized(lock) { "$epoch-${++created}" }

    /** Whether an item [id] exists. */
    operator fun contains(id: String): Boolean = synchronized(lock) { id in positions }

    /** The item [id], null when there is none. */
    operator fun get(id: String): AgentItem? = synchronized(lock) { positions[id]?.let(items::get) }

    /** The latest item, null for an empty transcript. */
    fun last(): AgentItem? = synchronized(lock) { items.lastOrNull() }

    /** The items not finished yet that [select] picks, to close them when a prompt ends. */
    fun <T : AgentItem> filter(select: (AgentItem) -> T?): List<T> = synchronized(lock) { items.mapNotNull(select) }

    /** Adds the item [create] builds with the version of this change. */
    fun add(create: (version: Long) -> AgentItem): AgentItem =
        synchronized(lock) {
            val item = create(changes.value + 1)
            check(item.id !in positions) { "Item ${item.id} already exists" }
            positions[item.id] = items.size
            items.add(item)
            changes.value = item.version
            item
        }

    /** Replaces item [id] by what [change] makes of it, stamped with the version of this change; false when missing. */
    fun update(id: String, change: (item: AgentItem, version: Long) -> AgentItem): Boolean =
        synchronized(lock) {
            val position = positions[id] ?: return false
            val version = changes.value + 1
            items[position] = change(items[position], version)
            changes.value = version
            true
        }

    /** Changes the [header]; a change makes a new version. */
    fun describe(change: (Header) -> Header) {
        synchronized(lock) {
            val changed = change(header)
            if (changed == header) return
            header = changed
            changes.value += 1
        }
    }

    fun setState(state: AgentTurnState) = describe { it.copy(state = state) }

    /** The version, the state and the items changed after [since], read together. */
    fun since(since: Long): Snapshot =
        synchronized(lock) {
            Snapshot(changes.value, header, items.filter { it.version > since })
        }

    /** What [since] returns. */
    class Snapshot(val version: Long, val header: Header, val items: List<AgentItem>)

    /** What a transcript says besides its items. */
    data class Header(
        val state: AgentTurnState = AgentTurnState.Idle,
        /** Name and version of the agent, once it introduced itself. */
        val agentName: String? = null,
        /** Id of the session of the agent. */
        val session: String? = null,
        /** Title of the session, from the agent. */
        val title: String? = null,
        /** Whether the items start with the history the agent replayed. */
        val replayed: Boolean = false,
        /** The settings of the session (its model…), as the agent last said, or as the user chose since. */
        val options: List<AgentOption> = emptyList(),
        /** The commands the agent offers in the session. */
        val commands: List<AgentSlashCommand> = emptyList(),
        /** The context used and the cost, as the agent last said. */
        val usage: AgentUsage? = null,
    )
}

/** This item with another version. */
internal fun AgentItem.withVersion(version: Long): AgentItem =
    when (this) {
        is AgentItem.UserText -> copy(version = version)
        is AgentItem.AgentText -> copy(version = version)
        is AgentItem.Thought -> copy(version = version)
        is AgentItem.ToolCall -> copy(version = version)
        is AgentItem.Plan -> copy(version = version)
        is AgentItem.Permission -> copy(version = version)
        is AgentItem.Notice -> copy(version = version)
    }
