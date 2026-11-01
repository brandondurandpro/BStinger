package org.example.kotlineditor.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val THINKING_MILLIS = 700L
private const val TOOL_MILLIS = 900L
private const val SEARCH_LIMIT = 500
private const val MIN_WORD_LENGTH = 3

// File the simulated agent writes its notes into, at the root of its worktree.
private const val NOTES_FILE = "AGENT_NOTES.md"

/**
 * Stand-in for a model: it lists the files the agent of [conversation] works on, searches them for a word of the prompt
 * and writes a note there, so the tool windows show real files, a real change and a real diff on its branch.
 */
internal class SimulatedAgent(private val conversation: Conversation) {
    suspend fun answer(prompt: String) {
        delay(THINKING_MILLIS)
        val workspace = conversation.workspace
        if (workspace == null) {
            val reason = conversation.workspaceError ?: "its worktree is not ready"
            conversation.messages.add(AgentMessage.Agent("I cannot work on ${conversation.branch}: $reason."))
            return
        }
        val files = workspace.services.fileSystem
        tool("List", workspace.name) { "${files.list(workspace.root).size} entries" }
        val longest = prompt.split(Regex("\\W+")).filter { it.length >= MIN_WORD_LENGTH }.maxByOrNull { it.length }
        val word = longest ?: prompt.take(TITLE_WORD_LENGTH)
        tool("Search", "\"$word\"") {
            val matches = workspace.services.search.findInFiles(workspace.root, word, true, SEARCH_LIMIT)
            "${matches.size} matches in ${matches.distinctBy { it.entry.path }.size} files"
        }
        tool("Edit", NOTES_FILE) { writeNotes(workspace, prompt) }
        val branch = conversation.currentBranch ?: "the current branch"
        val place = if (conversation.mode == WorkMode.Direct) "the project, on $branch," else "the worktree of $branch"
        // The tree, the Git panel and the open editors pick the new note up at once.
        workspace.synchronize()
        delay(THINKING_MILLIS)
        conversation.messages.add(
            AgentMessage.Agent(
                "This is a simulated answer: no model is connected yet.\n\n" +
                    "I searched $place for “$word” and wrote a note in $NOTES_FILE: " +
                    "the Git panel shows it, ready to be committed on this branch.",
            ),
        )
    }

    // A card for the tool, then its result (or its failure) once it ran.
    private suspend fun tool(name: String, target: String, run: suspend () -> String) {
        val card = AgentMessage.ToolCall(name, target)
        conversation.messages.add(card)
        delay(TOOL_MILLIS)
        card.output =
            runCatching { run() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrElse { "failed: ${it.message}" }
        card.running = false
    }

    private suspend fun writeNotes(workspace: ProjectState, prompt: String): String {
        val files = workspace.services.fileSystem
        val file =
            files.list(workspace.root).firstOrNull { !it.isDirectory && it.name == NOTES_FILE }
                ?: files.createFile(workspace.root, NOTES_FILE)
        val before = files.read(file).textOrNull().orEmpty()
        val notes = listOf("## ${titleOf(prompt)}", "", "Notes of the simulated agent about this request.", "")
        val separator =
            when {
                before.isEmpty() -> "# Agent notes\n\n"
                before.endsWith("\n") -> "\n"
                else -> "\n\n"
            }
        files.write(file, before + separator + notes.joinToString("\n"))
        return "+${notes.size} lines"
    }

    private companion object {
        const val TITLE_WORD_LENGTH = 10
    }
}
