package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentItem

/** The prompts already sent in this conversation, newest first. */
private fun Conversation.sentPrompts(): List<String> =
    items.filterIsInstance<AgentItem.UserText>().map { it.text }.asReversed()

/**
 * Up in an empty draft (or in a recalled prompt not edited since) brings back the previous prompt, Down the next one,
 * the way a shell history does. Returns true when the key was used.
 */
fun Conversation.recallPrompt(older: Boolean): Boolean {
    val sent = sentPrompts()
    val shown = sent.getOrNull(recalled)
    val recalling = recalled >= 0 && draft == shown
    val from = if (recalling) recalled else -1
    val editable = draft.isEmpty() || recalling
    val target = if (older) from + 1 else from - 1
    val inRange = target >= -1 && target < sent.size
    val used = editable && inRange && (older || recalling)
    if (used) {
        recalled = target
        draft = sent.getOrNull(target).orEmpty()
    }
    return used
}
