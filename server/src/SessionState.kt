package org.example.kotlineditor.server

import org.example.kotlineditor.rpc.Frame
import java.util.concurrent.ConcurrentHashMap

/** What a feature may use to build its state of a session: the way to push a frame to the session's client. */
class SessionContext internal constructor(
    /** Sends a frame to the session's client, when it is connected. */
    val push: (Frame) -> Unit,
)

/**
 * The state a feature keeps for each session of a client (its terminals, its runs…), created on first use by
 * [create] and closed when the session ends. Compare by identity: declare one key per feature, in a `val`.
 */
class SessionKey<T : AutoCloseable>(
    /** What the key is for, in diagnostics. */
    val name: String,
    internal val create: (SessionContext) -> T,
)

/** The states of one session. */
internal class SessionStates(push: (Frame) -> Unit) {
    private val context = SessionContext(push)
    private val states = ConcurrentHashMap<SessionKey<*>, AutoCloseable>()

    @Suppress("UNCHECKED_CAST")
    fun <T : AutoCloseable> stateOf(key: SessionKey<T>): T = states.computeIfAbsent(key) { key.create(context) } as T

    fun closeAll() {
        val open = states.values.toList()
        states.clear()
        open.forEach { it.close() }
    }
}
