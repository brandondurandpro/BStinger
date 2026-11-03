package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentCommand

/** The agent started when none is configured: Claude Code, through its ACP adapter (needs Node.js). */
const val DEFAULT_AGENT_COMMAND = "npx -y @agentclientprotocol/claude-agent-acp"

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
            closing -> quote = null
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

/** The command of [line], or of [DEFAULT_AGENT_COMMAND] when it is blank. */
fun agentCommandOf(line: String): AgentCommand {
    val words = commandWords(line)
    return AgentCommand(words.ifEmpty { commandWords(DEFAULT_AGENT_COMMAND) })
}
