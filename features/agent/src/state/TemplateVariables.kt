package org.example.kotlineditor.state

import java.time.LocalDate

/** The variables a prompt template may hold, with what each one stands for. */
val TEMPLATE_VARIABLES =
    listOf(
        "changed_files" to "The files with uncommitted changes, one per line",
        "open_file" to "The file shown in the editor",
        "open_files" to "All the files open in the editor, one per line",
        "selection" to "The text selected in the editor",
        "branch" to "The branch of the conversation",
        "project" to "The name of the project folder",
        "date" to "Today, as 2026-11-08",
    )

private val VARIABLE = Regex("""\{([a-z_]+)}""")

/**
 * [text] with its variables (`{changed_files}`, `{open_file}`…) replaced by their value for [conversation] now. A
 * variable that does not exist stays as typed; one without value (no file open, no selection) becomes empty.
 */
fun AgentState.expandTemplate(text: String, conversation: Conversation): String =
    VARIABLE.replace(text) { found ->
        val name = found.groupValues.getOrNull(1).orEmpty()
        variableValue(name, conversation) ?: found.value
    }

private fun AgentState.variableValue(name: String, conversation: Conversation): String? {
    val workspace = conversation.workspace ?: project
    val editor = workspace.editor
    return when (name) {
        "changed_files" -> conversation.changes.files.joinToString("\n") { it.path }
        "open_file" -> editor.activeTab?.relativePath.orEmpty()
        "open_files" -> editor.allTabs.filter { !it.isDiff && !it.isCompare }.joinToString("\n") { it.relativePath }
        "selection" -> editor.activeTab?.document?.selectedText().orEmpty()
        "branch" -> workspace.checkedOutBranch ?: conversation.branch
        "project" -> project.root.name
        "date" -> LocalDate.now().toString()
        else -> null
    }
}
