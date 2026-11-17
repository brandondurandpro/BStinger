package org.example.kotlineditor.model

/**
 * The text conventions of a team conversation, shared by the harness, which writes them, and the IDE, which reads
 * them back: the prompts the manager receives (the text of the user comes last, under [REQUEST_HEADING]; the prompts
 * the harness writes on its own start with one of [HARNESS_OPENINGS]) and the fenced `team` block the manager
 * delegates with. An agent that replays its session gives these texts back as they were sent, so the IDE must tell
 * what the user typed from what was added around it.
 */
object TeamText {
    /** The heading of the last section of every prompt the harness sends: the text the agent is asked to act on. */
    const val REQUEST_HEADING = "# User request"

    /** How the prompt that relays the reports of the members to the manager starts. */
    const val RELAY_OPENING = "Your members finished the tasks of this round."

    /** How the prompt that tells the manager its delegation could not be run starts. */
    const val REFUSAL_OPENING = "Your last message could not be run as a delegation."

    /** The first words of the prompts the harness sends the manager on its own: no message of the user. */
    val HARNESS_OPENINGS = listOf(RELAY_OPENING, REFUSAL_OPENING)

    /** The line that opens the block a manager delegates with. */
    const val BLOCK_OPENING = "```team"

    /** The line that closes it. */
    const val BLOCK_CLOSING = "```"

    /** A prompt of the harness: [intro] (when there is one), then the last section, [request]. */
    fun wrap(intro: String, request: String): String {
        val head = if (intro.isBlank()) "" else "$intro\n\n"
        return "$head$REQUEST_HEADING\n\n$request"
    }

    /**
     * What the user typed in [prompt], a prompt the manager received: the text after its last [REQUEST_HEADING] line,
     * or all of it when it has none (it was sent as typed). Null for a prompt the harness wrote on its own (the
     * reports of the members, a delegation it could not run), which is no message of the user.
     */
    fun typed(prompt: String): String? {
        val lines = prompt.lines()
        val heading = lines.indexOfLast { it.trim() == REQUEST_HEADING }
        if (heading < 0) return prompt
        val before = lines.take(heading).joinToString("\n").trim()
        val ownPrompt = HARNESS_OPENINGS.any { before.startsWith(it) }
        if (ownPrompt) return null
        return lines.drop(heading + 1).joinToString("\n").trim()
    }

    /**
     * [answer], an answer of a manager, without its `team` blocks: what it says to the user. A block still being
     * written (its closing line did not come yet, or its opening line is not complete) is left out too, so that an
     * answer that streams never shows a piece of one. An answer without a block is returned as it is.
     */
    fun withoutBlocks(answer: String): String {
        val lines = answer.lines()
        val kept = ArrayList<String>()
        var inside = false
        for (line in lines) {
            val text = line.trim()
            when {
                inside -> inside = text != BLOCK_CLOSING
                text == BLOCK_OPENING -> inside = true
                else -> kept.add(line)
            }
        }
        val last = kept.lastOrNull()?.trim().orEmpty()
        val opening = last.length > BLOCK_CLOSING.length && BLOCK_OPENING.startsWith(last)
        if (opening) kept.removeLast()
        if (kept.size == lines.size) return answer
        return kept.joinToString("\n").trim()
    }

    /** The key of the conversation of the member [memberId] of the team conversation [conversation]. */
    fun memberConversation(conversation: String, memberId: String): String = "$conversation#$memberId"
}
