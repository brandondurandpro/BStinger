package org.example.kotlineditor.testkit.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.TeamText

/**
 * How a [FakeAgent] finds what it is asked to do when the prompt is wrapped: a harness that puts a preamble in front of
 * the text of the user ends it with a line that is exactly [MARKER], followed by the text of the user. The directive of
 * a prompt is the text after the last such line, trimmed; a prompt without one is its own directive, so every prompt
 * written before wrapping existed means what it meant.
 */
object FakeDirective {
    /**
     * The line that ends the preamble of a wrapped prompt, alone on its line (surrounding spaces ignored): the
     * heading the harness of the teams puts before the request.
     */
    const val MARKER = TeamText.REQUEST_HEADING

    /** The directive of [prompt]. */
    fun of(prompt: String): String {
        val lines = prompt.lines()
        val marked = lines.indexOfLast { it.trim() == MARKER }
        if (marked < 0) return prompt
        return lines.drop(marked + 1).joinToString("\n").trim()
    }

    /** [text] as a harness wraps it: [preamble], the [MARKER] line, then [text]. */
    fun wrap(preamble: String, text: String): String = "$preamble\n\n$MARKER\n\n$text"
}

/** One task of a manager's round: what it asks of [member]. */
data class FakeTask(val member: String, val task: String)

/**
 * What a [FakeAgent] plays as a team manager: the rounds of tasks it delegates, one per turn, from a directive
 *
 * - `delegate <member>: <task> | <member>: <task> ;; <member>: <task>`: tasks of a round are separated by ` | `, rounds
 *   by ` ;; `; a member name is not checked (`delegate Nobody: echo hi` names one the team does not have);
 * - `delegate-malformed`: a `team` block that is not JSON.
 *
 * The first round is delegated at once: the answer is a sentence, then the fenced `team` block
 * `{"delegate": [{"member": …, "task": …}]}`. Every later prompt of the session (the reports of the members, whatever
 * its text) is answered with the next round, and once the rounds are used up with `Final answer. Reports received: `
 * followed by the directive of that prompt, so that a test sees what the manager was told.
 */
class FakeManager private constructor(private val rounds: ArrayDeque<List<FakeTask>>, private val malformed: Boolean) {
    /** Whether a round is left to delegate. */
    fun hasRound(): Boolean = malformed.not() && rounds.isNotEmpty()

    /** The answer that delegates the next round (the malformed block for a malformed script). */
    fun delegate(): String {
        if (malformed) return "I will delegate.\n\n```team\n{\"delegate\": [oops\n```"
        val round = rounds.removeFirstOrNull().orEmpty()
        val tasks = round.map { task -> buildJsonObject { put("member", task.member); put("task", task.task) } }
        val block = buildJsonObject { put("delegate", JsonArray(tasks)) }
        return "I will delegate.\n\n```team\n$block\n```"
    }

    /** The answer once the rounds are over, to the [reports] it received. */
    fun finalAnswer(reports: String): String = "$FINAL_PREFIX$reports"

    companion object {
        /** What the final answer of a manager starts with. */
        const val FINAL_PREFIX = "Final answer. Reports received: "

        private const val DELEGATE = "delegate"
        private const val MALFORMED = "delegate-malformed"
        private const val ROUND_SEPARATOR = " ;; "
        private const val TASK_SEPARATOR = " | "
        private const val MEMBER_SEPARATOR = ": "

        /** The manager a directive scripts, null when it is not one of its directives. */
        fun parse(directive: String): FakeManager? {
            val words = directive.trim().split(Regex("\\s+"), limit = 2)
            val command = words.firstOrNull()
            if (command == MALFORMED) return FakeManager(ArrayDeque(), malformed = true)
            if (command != DELEGATE) return null
            val script = words.getOrNull(1).orEmpty()
            val rounds = script.split(ROUND_SEPARATOR).map(::round)
            return FakeManager(ArrayDeque(rounds), malformed = false)
        }

        private fun round(text: String): List<FakeTask> =
            text.split(TASK_SEPARATOR).map { entry ->
                val member = entry.substringBefore(MEMBER_SEPARATOR).trim()
                val task = entry.substringAfter(MEMBER_SEPARATOR, "").trim()
                FakeTask(member, task)
            }
    }
}
