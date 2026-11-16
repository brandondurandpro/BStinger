package org.example.kotlineditor.harness

import org.example.kotlineditor.model.AgentAttachment
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A prompt of the user that waits for the end of the run in progress. */
internal class QueuedPrompt(
    val itemId: String,
    val text: String,
    val session: String?,
    val attachments: List<AgentAttachment>,
)

/** The conversation of a member: kept for the whole team conversation, so that the member remembers its tasks. */
internal class MemberSession(val key: String, val member: TeamAgent) {
    /** Whether its settings were applied (set before they are, so that a later change of option reaches it). */
    @Volatile
    var configured = false

    /** Whether it already got its first prompt, the one that carries its preamble. */
    @Volatile
    var prompted = false
}

/**
 * What the harness keeps of one team conversation, until it is closed: its team (locked at the first prompt), the
 * prompts of the user waiting for the run in progress, the ids it accepted (a prompt sent twice runs once) and the
 * sessions of its members.
 */
internal class TeamConversation(val key: String, val team: Team, val directory: String) {
    /** Guards [seen], [queue] and [running]. */
    val lock = Any()
    val seen = HashSet<String>()
    val queue = ArrayDeque<QueuedPrompt>()

    /** Whether a run is going on (from its acceptance to the end of the last queued run). */
    var running = false

    /** Whether the conversation was closed: nothing more is written to it. */
    @Volatile
    var closed = false

    /** The members' sessions, by member id; created at their first task. */
    val members = ConcurrentHashMap<String, MemberSession>()

    /** Whether the manager was configured and got its first prompt (only the run in progress touches it). */
    var managerStarted = false

    private val counter = AtomicLong()

    /** A new id for a prompt or an item the harness makes, unique in this conversation. */
    fun nextId(kind: String): String = "team-$kind-${counter.incrementAndGet()}"

    /** The key of the conversation of [member]. */
    fun memberKey(member: TeamAgent): String = "$key#${member.id}"
}
