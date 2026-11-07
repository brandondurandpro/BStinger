package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentItem

/** The conversation [items], as the user copies it: the messages of the transcript, one block each, Markdown. */
fun markdownOf(items: List<AgentItem>): String = buildString {
    for (item in items) {
        append(item.toMarkdown())
        append("\n\n")
    }
}

// One block of the message [item]: Markdown, a paragraph, a heading, or a list of actions.
private fun AgentItem.toMarkdown(): String =
    when (this) {
        is AgentItem.UserText -> said("You", text, context)
        is AgentItem.AgentText -> text.trim()
        is AgentItem.Thought -> quoteLines(text.trimEnd())
        is AgentItem.ToolCall -> toolLines(this)
        is AgentItem.Plan -> planLines(this)
        is AgentItem.Permission -> "- **Permission**: $title${offer()}"
        is AgentItem.Notice -> quoteLines(text)
    }

// A heading, the text under it, and what was joined to the prompt (`You` — the files joined, between parentheses).
private fun said(heading: String, text: String, context: List<String>): String {
    val said = if (text.isBlank()) "*(no text)*" else text.trim()
    return "## $heading\n$said${attached(context)}"
}

// What was joined to the prompt, for the reader that looks for it.
private fun attached(context: List<String>): String =
    when (val joined = context.takeIf { it.isNotEmpty() }) {
        null -> ""
        else -> "\n\n*(${joined.joinToString(", ")})*"
    }

// The request of the agent and what came of the user's answer, when there was one.
private fun AgentItem.Permission.offer(): String =
    when {
        chosen != null -> ": $chosen"
        else -> ""
    }

// The text under quotes, each line of it prefixed once.
private fun quoteLines(text: String): String = text.split('\n').joinToString("\n") { line -> "> $line" }.trim()

// One action: what was done, and the output it left, indented.
private fun toolLines(call: AgentItem.ToolCall): String {
    val head = "- **Action**: ${call.title} (${call.status.name.lowercase()})"
    val said = saidOf(call.output)
    return when {
        said == null -> head
        else -> "$head\n$said"
    }
}

// The output the tool left, each line of it indented once; null when it has nothing to say.
private fun saidOf(output: String): String? {
    val said = output.trim().takeUnless { it.isBlank() } ?: return null
    return said.split('\n').joinToString("\n") { line -> "    $line" }
}

// The plan: one checkbox by step, the one it has done checked.
private fun planLines(plan: AgentItem.Plan): String {
    val boxes = mutableListOf("## Plan")
    plan.entries.forEach { entry ->
        val mark = if (entry.status == "completed") "x" else " "
        boxes.add("- [$mark] ${entry.content}")
    }
    return boxes.joinToString("\n")
}
