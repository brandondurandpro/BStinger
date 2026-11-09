package org.example.kotlineditor.state

/** A prompt the user saved to send again: [name] says what it is for, [text] goes into the prompt; [global]: in every project, else in this one. */
data class PromptTemplate(val name: String, val text: String, val global: Boolean = true)

private const val ESCAPE = '\\'

private fun escaped(text: String): String =
    text.replace("$ESCAPE", "$ESCAPE$ESCAPE").replace("\n", "${ESCAPE}n").replace("\t", "${ESCAPE}t")

private fun unescaped(text: String): String {
    val out = StringBuilder()
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val next = text.getOrNull(index + 1)
        if (char == ESCAPE && next != null) {
            out.append(
                when (next) {
                    'n' -> '\n'
                    't' -> '\t'
                    else -> next
                },
            )
            index += 2
        } else {
            out.append(char)
            index++
        }
    }
    return out.toString()
}

/** The templates as saved: one per line. */
internal fun encodeTemplates(templates: List<PromptTemplate>): String =
    templates.joinToString("\n") { "${escaped(it.name)}\t${escaped(it.text)}" }

/** The templates saved by [encodeTemplates]; a damaged line is ignored. */
internal fun decodeTemplates(saved: String?, global: Boolean = true): List<PromptTemplate> =
    saved.orEmpty().lines().mapNotNull { line ->
        val name = unescaped(line.substringBefore('\t'))
        val text = unescaped(line.substringAfter('\t', ""))
        PromptTemplate(name, text, global).takeIf { name.isNotBlank() && text.isNotBlank() }
    }
