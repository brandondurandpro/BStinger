package org.example.kotlineditor.ui

/** The tokens of a glob field: its comma-separated patterns (commas inside braces do not split), trimmed. */
internal fun globTokens(text: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    for (char in text) {
        when {
            char == '{' -> depth++
            char == '}' -> depth = maxOf(0, depth - 1)
        }
        if (char == ',' && depth == 0) {
            tokens += current.toString()
            current.clear()
        } else {
            current.append(char)
        }
    }
    tokens += current.toString()
    return tokens.map { it.trim() }.filter { it.isNotEmpty() }
}

/** [text] with [token] added at its end, or removed when it is already there: what a suggestion chip does. */
internal fun toggleGlobToken(text: String, token: String): String {
    val tokens = globTokens(text)
    val result = if (token in tokens) tokens - token else tokens + token
    return result.joinToString(", ")
}
