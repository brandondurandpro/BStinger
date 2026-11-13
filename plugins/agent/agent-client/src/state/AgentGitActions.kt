package org.example.kotlineditor.state

/**
 * What the user can ask the agent to do with the work of a conversation in Git. The agent does it itself, with its
 * own tools, so it writes the message, handles hooks and tells what went wrong; the IDE only sends the request.
 */
enum class GitAction(val label: String, val prompt: String) {
    Commit(
        "Commit",
        "Commit the current changes of this working directory. Write a clear commit message in the style of the " +
            "recent commits of the repository. Do not push anything.",
    ),
    CommitAndPush(
        "Commit and push",
        "Commit the current changes of this working directory with a clear commit message in the style of the " +
            "recent commits of the repository, then push the current branch to its remote (set its upstream " +
            "if it has none).",
    ),
    Push(
        "Push",
        "Push the current branch to its remote (set its upstream if it has none). Do not commit or change " +
            "anything else.",
    ),
    CommitPushAndPullRequest(
        "Commit, push and open a pull request",
        "Commit the current changes of this working directory with a clear commit message, push the current " +
            "branch to its remote (set its upstream if it has none), then open a pull request for it with the " +
            "`gh` CLI, with a title and a description of what changed. If `gh` is not available, say so and " +
            "stop after the push.",
    ),
}

/** Asks the agent of [conversation] to do [action]; what the user typed in the prompt is kept. */
fun AgentState.ask(conversation: Conversation, action: GitAction) {
    val typed = conversation.draft
    conversation.draft = action.prompt
    send(conversation)
    conversation.draft = typed
}
