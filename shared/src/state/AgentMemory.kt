package org.example.kotlineditor.state

import androidx.compose.runtime.Stable

/**
 * Which agent each conversation ran with, as the server said it while its session ran: the IDE remembers the id of
 * the agent of the ACP registry of each session, the recent ones — so a conversation reopened from the history comes
 * back with its agent even after the IDE restarted. A session that ran the agent of the settings is remembered as
 * none: the settings still say it.
 */
@Stable
class AgentMemory(
    /** Reads the preference, as saved; null when nothing was saved. The IDE has one for its whole window. */
    private val read: (key: String) -> String? = { null },
    /** Writes the preference, saved right away. */
    private val write: (key: String, value: String) -> Unit = { _, _ -> },
) {
    /** The ids of the agents of the registry remembered, the recent ones first, each once. */
    val registries: List<String>
        get() = decoded().mapNotNull { it.registry }.distinct()

    /** The id of the agent of the registry that [session] ran with; null when it is unknown, or the settings'. */
    fun agentOf(session: String): String? {
        val entry = decoded().firstOrNull { it.session == session }
        return entry?.registry
    }

    /** Remembers that [session] ran with the agent [entry] of the registry; null forgets it (the settings' one). */
    fun remember(session: String, entry: String?) {
        val others = decoded().filterNot { it.session == session }
        val kept =
            if (entry == null) others.filter { it.registry != null } else others + Entry(session, entry)
        val encoded = encoded(kept.takeLast(KEPT))
        if (encoded == current()) return
        write(KEY, encoded)
    }

    // The sessions remembered, as the preference says them.
    private fun decoded(): List<Entry> {
        val words = emptyWords(current())
        val entries = mutableListOf<Entry>()
        var index = 0
        while (index + 1 < words.size) {
            val session = words[index]
            val registry = words[index + 1]
            val known = session.isNotEmpty() && registry != SETTINGS_AGENT
            if (known) entries.add(Entry(session, registry))
            index += 2
        }
        return entries
    }

    // The preference, as words: a session and the registry of its agent, in turn.
    private fun emptyWords(saved: String?): List<String> =
        saved
            ?.takeIf { !it.isBlank() }
            ?.trim()
            ?.split(' ')
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private fun encoded(entries: List<Entry>): String = entries.joinToString(" ") { wordOf(it) }

    // A session and the registry of its agent, the one of the settings standing for the agent it was.
    private fun wordOf(entry: Entry): String {
        val registry = entry.registry ?: SETTINGS_AGENT
        return "${entry.session} $registry"
    }

    private fun current(): String? = read(KEY)

    /** A session of the past and the agent of the registry it ran with, null for the one of the settings. */
    data class Entry(val session: String, val registry: String?)

    companion object {
        const val KEY = "agent.sessions"

        /** Sessions remembered: the oldest ones are dropped. */
        const val KEPT = 40

        /** Stands for the agent of the settings, which the preferences say themselves. */
        const val SETTINGS_AGENT = "settings.agent"
    }
}
