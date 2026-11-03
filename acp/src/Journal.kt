package org.example.kotlineditor.acp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentTurnState

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

    var state: AgentTurnState = AgentTurnState.Idle
        private set

    /** Name and version of the agent, once it introduced itself. */
    var agentName: String? = null
        private set

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

    fun setState(state: AgentTurnState) {
        synchronized(lock) {
            if (this.state == state) return
            this.state = state
            changes.value += 1
        }
    }

    fun setAgentName(name: String?) {
        synchronized(lock) {
            if (agentName == name) return
            agentName = name
            changes.value += 1
        }
    }

    /** The version, the state and the items changed after [since], read together. */
    fun since(since: Long): Snapshot =
        synchronized(lock) {
            Snapshot(changes.value, state, agentName, items.filter { it.version > since })
        }

    /** What [since] returns. */
    class Snapshot(val version: Long, val state: AgentTurnState, val agentName: String?, val items: List<AgentItem>)
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
