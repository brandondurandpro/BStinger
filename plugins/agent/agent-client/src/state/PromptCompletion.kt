package org.example.kotlineditor.state

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.example.kotlineditor.model.AgentSlashCommand

// What the prompt completes as the user types: a slash command at its start (`/com` → `/compact `), a file after an
// `@` anywhere (`@Main` → the file, attached to the prompt).

/** The word being completed at the caret of the prompt, from [start] to the caret. */
sealed interface Completing {
    /** What is typed after the `/` or the `@`. */
    val query: String

    /** Where the word starts, its `/` or `@` included. */
    val start: Int

    /** A slash command, typed at the start of the prompt. */
    data class Command(override val query: String, override val start: Int) : Completing

    /** A file of the project, typed after an `@`. */
    data class File(override val query: String, override val start: Int) : Completing
}

/** What [value] completes at its caret; null when the caret is not at the end of a `/command` or an `@file`. */
fun completingAt(value: TextFieldValue): Completing? {
    val caret = value.selection.start
    val text = value.text
    if (!value.selection.collapsed) return null
    val start = text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), (caret - 1).coerceAtLeast(0)).let { it + 1 }
    val word = text.substring(start.coerceAtMost(caret), caret)
    return when {
        word.startsWith("/") && start == 0 -> Completing.Command(word.drop(1), start)
        word.startsWith("@") -> Completing.File(word.drop(1), start)
        else -> null
    }
}

/** The commands whose name starts with [query], then those that contain it, in the agent's order. */
fun commandsMatching(commands: List<AgentSlashCommand>, query: String): List<AgentSlashCommand> {
    val starting = commands.filter { it.name.startsWith(query, ignoreCase = true) }
    val containing = commands.filter { it.name.contains(query, ignoreCase = true) && it !in starting }
    return starting + containing
}

/** [value] with the word of [completing] replaced by [replacement], the caret after it. */
fun replaced(value: TextFieldValue, completing: Completing, replacement: String): TextFieldValue {
    val caret = value.selection.start
    val text = value.text.substring(0, completing.start) + replacement + value.text.substring(caret)
    return TextFieldValue(text, TextRange(completing.start + replacement.length))
}
