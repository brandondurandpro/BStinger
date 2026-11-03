package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentCommand

/** The agent of the ACP registry started when none is chosen: Claude, through its ACP adapter. */
const val DEFAULT_AGENT = "claude-acp"

/**
 * The words of a command line typed in the settings: split on spaces, with double or single quotes around a word
 * that has spaces (`"C:\Program Files\agent.exe" --acp`). Variables are not expanded: the server runs the program
 * itself, without a shell.
 */
fun commandWords(line: String): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    var quote: Char? = null
    var started = false
    for (char in line) {
        val closing = quote != null && char == quote
        val opening = quote == null && (char == '"' || char == '\'')
        val separator = quote == null && char.isWhitespace()
        when {
            closing -> {
                quote = null
            }

            opening -> {
                quote = char
                started = true
            }

            separator -> {
                if (started) words.add(word.toString())
                word.clear()
                started = false
            }

            else -> {
                word.append(char)
                started = true
            }
        }
    }
    if (started) words.add(word.toString())
    return words
}

/**
 * The agent to start: the command [line] when one is typed, otherwise the agent [registry] of the ACP registry
 * (the server installs it), [DEFAULT_AGENT] when none is chosen.
 */
fun agentCommandOf(line: String, registry: String = ""): AgentCommand {
    val words = commandWords(line)
    if (words.isNotEmpty()) return AgentCommand(words)
    return AgentCommand(emptyList(), registry = registry.ifBlank { DEFAULT_AGENT })
}
