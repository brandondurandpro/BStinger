package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentItem

/** The text of an item that people read and search: what was said, not the tool output. */
private fun AgentItem.searchable(): String =
    when (this) {
        is AgentItem.UserText -> text
        is AgentItem.AgentText -> text
        is AgentItem.Thought -> text
        is AgentItem.ToolCall -> title
        is AgentItem.Plan -> ""
        is AgentItem.Permission -> title
        is AgentItem.Notice -> text
    }

/** True when [query] (blank matches all) is in the title of the conversation or in one of its messages. */
fun Conversation.matches(query: String): Boolean {
    val wanted = query.trim()
    if (wanted.isEmpty()) return true
    val inTitle = title.contains(wanted, ignoreCase = true)
    return inTitle || items.any { it.searchable().contains(wanted, ignoreCase = true) }
}
