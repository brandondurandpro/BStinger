package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentItem

private const val REQUEST_LENGTH = 1_500

/**
 * Starts a conversation in which another agent reviews the work of [conversation]: it works where [conversation]
 * does (the same worktree, or the project), reads the changes with Git and reports what it finds without touching a
 * file. [entry] is the agent of the registry that reviews, null for the agent of the settings. The review is sent at
 * once and the new conversation is shown.
 */
fun AgentState.reviewWith(conversation: Conversation, entry: String?): Conversation {
    val reviewer = Conversation(nextId++, "Review: ${conversation.title}", conversation.branch)
    reviewer.onTurnIdle = { deliverQueued(reviewer) }
    // The same branch, so the server hands the worktree of the reviewed conversation over, not a new one.
    reviewer.mode = conversation.mode
    conversations.add(0, reviewer)
    open(reviewer)
    chooseAgent(reviewer, entry, remember = false)
    reviewer.draft = reviewPrompt(conversation)
    send(reviewer)
    reviewer.title = "Review: ${conversation.title}"
    reviewer.renamed = true
    return reviewer
}

/** What the reviewer is asked: the request of the user, where the changes are, and what to report (and not to do). */
internal fun reviewPrompt(conversation: Conversation): String {
    val request = firstRequestOf(conversation)
    val branch = conversation.currentBranch ?: conversation.branch
    return buildString {
        appendLine("Another agent just worked in this repository (branch `$branch`). Review its work; do not change any file.")
        if (request.isNotBlank()) {
            appendLine()
            appendLine("What it was asked:")
            appendLine(request)
        }
        appendLine()
        appendLine("1. See what changed: `git status`, `git diff HEAD`, and `git log` for the commits of this branch.")
        appendLine("2. Read the changed files, and what they touch, as much as you need.")
        appendLine("3. Report, by file and line: bugs, missed cases, risky changes, missing tests, and anything that")
        appendLine("   does not do what was asked. Say first whether the work is ready, then the problems, worst first.")
        append("Only read and run read-only commands: the other agent's files stay as they are.")
    }
}

// The first prompt of the user in [conversation], cut when long.
private fun firstRequestOf(conversation: Conversation): String {
    val first = conversation.items.filterIsInstance<AgentItem.UserText>().firstOrNull()
    val text = first?.text.orEmpty().trim()
    return if (text.length <= REQUEST_LENGTH) text else text.take(REQUEST_LENGTH).trimEnd() + "…"
}
