package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.FileEntry

private const val REQUEST_LENGTH = 1_500

/** What a review by an agent looks at: the user ticks the aspects that matter, each one adds a task to the prompt. */
enum class ReviewAspect(val label: String, val task: String) {
    Correctness("Bugs and correctness", "Look for bugs and logic errors: does the code do what was asked, in every path?"),
    EdgeCases("Edge cases", "Look for missed edge cases: empty or huge inputs, nulls, errors, cancellation, concurrency."),
    Quality("Code quality", "Judge the quality: naming, structure, duplication, readability, consistency with the code around."),
    Duplication("Duplication", "Look for duplicated code: copy-pasted blocks in the change, and existing helpers or components it should reuse."),
    Simplicity("Simplicity", "Look for what could be simpler: needless abstractions, dead code, a smaller change giving the same result."),
    Tests("Tests", "Check the tests: is every behavior and edge case covered, would a test fail if the change were wrong?"),
    Security("Security", "Look for security problems: injection, unsafe paths, secrets, missing validation."),
    Performance("Performance", "Look for performance problems: needless work, quadratic loops, memory growth, blocking calls."),
    Compatibility("Compatibility and side effects", "Check what else the change touches: callers, public behavior, other platforms, data on disk."),
}

/** The aspects ticked when the dialog opens. */
val DEFAULT_REVIEW_ASPECTS: Set<ReviewAspect> =
    setOf(ReviewAspect.Correctness, ReviewAspect.EdgeCases, ReviewAspect.Quality, ReviewAspect.Simplicity, ReviewAspect.Tests)

/**
 * Opens the review tab, led by hand, of the files [conversation] changed where it works: each file has an OK box and
 * its diff. Nothing is sent to an agent.
 */
fun AgentState.reviewManually(conversation: Conversation) {
    val workspace = conversation.workspace ?: return
    val root = workspace.root.path.trimEnd('/')
    val files =
        conversation.changes.files.map { changed ->
            ReviewFile(FileEntry(path = "$root/${changed.path}", name = changed.name, isDirectory = false), changed.path)
        }
    workspace.editor.openReview(files)
    conversation.showsChat = false
}

/**
 * Starts a conversation in which another agent reviews the work of [conversation]: it works where [conversation]
 * does (the same worktree, or the project), reads the changes with Git and reports what it finds without touching a
 * file. [entry] is the agent of the registry that reviews, null for the agent of the settings; it looks at the
 * [aspects] only, with the [settings] (option id to value, the model…) its session starts with. The review is sent at once and the new conversation is shown.
 */
fun AgentState.reviewWith(
    conversation: Conversation,
    entry: String?,
    aspects: Set<ReviewAspect> = DEFAULT_REVIEW_ASPECTS,
    settings: Map<String, String> = emptyMap(),
): Conversation {
    val reviewer = Conversation(nextId++, "Review: ${conversation.title}", conversation.branch)
    reviewer.onTurnIdle = { deliverQueued(reviewer) }
    // The same branch, so the server hands the worktree of the reviewed conversation over, not a new one.
    reviewer.mode = conversation.mode
    reviewer.reviewOf = conversation
    conversations.add(0, reviewer)
    open(reviewer)
    chooseAgent(reviewer, entry, remember = false)
    // Sent to the session before the prompt, as the choices made in a conversation that has not started.
    reviewer.choices.putAll(settings)
    reviewer.draft = reviewPrompt(conversation, aspects)
    send(reviewer)
    reviewer.title = "Review: ${conversation.title}"
    reviewer.renamed = true
    return reviewer
}

/** What the reviewer answered to its last prompt, null while it has said nothing. */
fun reviewReport(reviewer: Conversation): String? {
    val lastPrompt = reviewer.items.indexOfLast { it is AgentItem.UserText }
    val answers = reviewer.items.drop(lastPrompt + 1).filterIsInstance<AgentItem.AgentText>()
    return answers.joinToString("\n\n") { it.text }.trim().takeIf { it.isNotEmpty() }
}

/**
 * Sends the report of [reviewer] to the agent whose work it reviewed, to fix what is wrong, and shows that
 * conversation. Nothing happens when the reviewer said nothing yet or the reviewed conversation is gone.
 */
fun AgentState.sendReviewToAuthor(reviewer: Conversation) {
    val author = reviewer.reviewOf ?: return
    if (author !in conversations) return
    val report = reviewReport(reviewer) ?: return
    val request = buildString {
        appendLine("Another agent reviewed your work. Its report:")
        appendLine()
        appendLine(report)
        appendLine()
        append("Check each point against the code: fix the ones that are real, and say which you left and why.")
    }
    deliverText(author, request)
    openChat(author)
}

/** What the reviewer is asked: the request of the user, where the changes are, and what to report (and not to do). */
internal fun reviewPrompt(conversation: Conversation, aspects: Set<ReviewAspect> = DEFAULT_REVIEW_ASPECTS): String {
    val chosen = ReviewAspect.entries.filter { it in aspects }
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
        appendLine("3. Analyze the work on these aspects, and only these:")
        for (aspect in chosen) appendLine("   - ${aspect.label}: ${aspect.task}")
        appendLine("4. Report, by file and line, what you found, and anything that does not do what was asked. Say first")
        appendLine("   whether the work is ready, then the problems, worst first.")
        append("Only read and run read-only commands: the other agent's files stay as they are.")
    }
}

// The first prompt of the user in [conversation], cut when long.
private fun firstRequestOf(conversation: Conversation): String {
    val first = conversation.items.filterIsInstance<AgentItem.UserText>().firstOrNull()
    val text = first?.text.orEmpty().trim()
    return if (text.length <= REQUEST_LENGTH) text else text.take(REQUEST_LENGTH).trimEnd() + "…"
}

/**
 * The model the agent [entry] (null: the agent of the settings) offers its sessions, to choose the one that reviews;
 * null when the agent offers none or the server cannot say.
 */
suspend fun AgentState.reviewerModels(entry: String?): AgentOption? {
    val command = entry?.let { AgentCommand(emptyList(), registry = it) } ?: agentOf(Conversation(-1, "", ""))
    val offered =
        try {
            project.services.agents.options(command, project.root.path)
        } catch (failed: java.io.IOException) {
            emptyList()
        }
    return offered.firstOrNull { it.category == AgentOption.MODEL }
}
