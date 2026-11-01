package org.example.kotlineditor.state

// Conversations shown when agent mode opens. They work on the current branch of the project until moved to a worktree
// of their own branch.

private fun AgentMessage.ToolCall.done(result: String) =
    apply {
        output = result
        running = false
    }

private fun conversation(id: Int, title: String, branch: String, vararg messages: AgentMessage): Conversation =
    Conversation(id, title, branch).apply { this.messages.addAll(messages) }

private fun explainProject(id: Int) =
    conversation(
        id,
        "Explain the layout of the project",
        "agent/explain-project",
        AgentMessage.User("How is this project organized?"),
        AgentMessage.ToolCall("List", ".").done("12 entries"),
        AgentMessage.Agent(
            "A simulated answer: open the Project tool window on the left to browse the files this " +
                "conversation works on, on the current branch or in a worktree of agent/explain-project.",
        ),
    )

private fun reviewReadme(id: Int) =
    conversation(
        id,
        "Review the README",
        "agent/review-readme",
        AgentMessage.User("Can you review the README?"),
        AgentMessage.ToolCall("Read", "README.md").done("read"),
        AgentMessage.Agent("A simulated answer: nothing changed yet. Ask something and I will write a note here."),
    )

/** Sample conversations, numbered from 1; the next new conversation takes the following number. */
internal fun sampleConversations(): List<Conversation> =
    listOf(::explainProject, ::reviewReadme).mapIndexed { index, sample -> sample(index + 1) }
