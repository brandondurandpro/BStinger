package org.example.kotlineditor.state

private const val MIN_WIDTH = 160f
private const val MAX_WIDTH = 640f

/** The width of the conversation list saved by the last run, within its limits. */
internal fun savedWidth(preferences: Map<String, String>): Float? =
    preferences[PreferenceKeys.AGENT_CONVERSATIONS_WIDTH]
        ?.toFloatOrNull()
        ?.takeIf { it.isFinite() }
        ?.coerceIn(MIN_WIDTH, MAX_WIDTH)

private fun linesOf(value: String?): List<String> = value.orEmpty().lines().filter { it.isNotBlank() }

/**
 * Puts back, once the history is listed, what the last run left of agent mode in the project at [root]: the titles
 * the user gave, then the conversations that were open in tabs, in their order, and the one that was active. Only the
 * active one starts its worktree and its session; the others do when their tab is selected.
 */
internal fun AgentState.restoreSaved(root: String) {
    if (restored) return
    restored = true
    val preferences = savedPreferences
    for (line in linesOf(preferences[PreferenceKeys.AGENT_TITLES_PREFIX + root])) {
        val session = line.substringBefore('\t')
        val title = line.substringAfter('\t', "")
        val titled = conversations.firstOrNull { it.session == session }
        if (titled != null && title.isNotBlank()) {
            titled.title = title
            titled.renamed = true
        }
    }
    val pinned = linesOf(preferences[PreferenceKeys.AGENT_PINNED_PREFIX + root]).toSet()
    conversations.filter { it.session in pinned }.forEach { it.pinned = true }
    val archived = linesOf(preferences[PreferenceKeys.AGENT_ARCHIVED_PREFIX + root]).toSet()
    conversations.filter { it.session in archived }.forEach { it.archived = true }
    val active = preferences[PreferenceKeys.AGENT_ACTIVE_PREFIX + root].orEmpty()
    for (session in linesOf(preferences[PreferenceKeys.AGENT_OPEN_PREFIX + root])) {
        val reopened = conversations.firstOrNull { it.session == session }
        if (reopened != null && session != active) tabs.open(reopened)
    }
    conversations.firstOrNull { it.session == active }?.let { open(it) }
}

/** What to save of agent mode in this project: the width of the list, the open tabs, the active one, the titles. */
fun AgentState.toPreferences(): Map<String, String> {
    val width = PreferenceKeys.AGENT_CONVERSATIONS_WIDTH to conversationsWidth.toString()
    val last = PreferenceKeys.AGENT_LAST_CHOSEN to lastAgent.orEmpty()
    val used = PreferenceKeys.AGENT_USED to usedAgentIds().joinToString("\n")
    val saved = PreferenceKeys.AGENT_TEMPLATES to encodeTemplates(templates)
    // Nothing of the tabs is saved before the last ones were put back: it would erase them.
    if (!restored) return mapOf(width, last, saved, used)
    val root = project.root.path
    val open = tabs.open.mapNotNull { it.session }.joinToString("\n")
    val active = tabs.active?.session.orEmpty()
    val titles =
        conversations
            .filter { it.renamed }
            .mapNotNull { conversation -> conversation.session?.let { "$it\t${oneLine(conversation.title)}" } }
            .joinToString("\n")
    val pinned = conversations.filter { it.pinned }.mapNotNull { it.session }.joinToString("\n")
    val archived = conversations.filter { it.archived }.mapNotNull { it.session }.joinToString("\n")
    return mapOf(
        width,
        last,
        saved,
        used,
        PreferenceKeys.AGENT_ARCHIVED_PREFIX + root to archived,
        PreferenceKeys.AGENT_PINNED_PREFIX + root to pinned,
        PreferenceKeys.AGENT_OPEN_PREFIX + root to open,
        PreferenceKeys.AGENT_ACTIVE_PREFIX + root to active,
        PreferenceKeys.AGENT_TITLES_PREFIX + root to titles,
    )
}

private fun oneLine(title: String): String = title.replace('\n', ' ').replace('\t', ' ')
