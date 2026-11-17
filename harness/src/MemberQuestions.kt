package org.example.kotlineditor.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.example.kotlineditor.model.TeamText

/** A question a member asks the user: [text], and the [choices] it proposes (none: the user answers in words). */
internal data class MemberQuestion(val text: String, val choices: List<String>)

/** What a member's message asks of the user: the [questions] of its `ask` block, and what it said around it. */
internal data class MemberAsk(val message: String, val questions: List<MemberQuestion>)

/** A question the user answered. */
internal class AnsweredQuestion(val question: MemberQuestion, val answer: String)

private const val QUESTIONS = "questions"
private const val QUESTION = "question"
private const val OPTIONS = "options"

// A member asks at most this many questions at once, and this many times in one task: it works, it does not interview.
internal const val MAX_QUESTIONS = 6
internal const val MAX_ASKS = 3

/**
 * What the message [answer] of a member asks of the user, from the last fenced `ask` block it ends with:
 *
 * ````
 * ```ask
 * {"questions": [{"question": "Which database?", "options": ["SQLite", "Postgres"]}]}
 * ```
 * ````
 *
 * Null when the message has no block, or one that cannot be read (it then counts as a report, which the manager gets
 * with the block taken out: the member's doubts are not lost).
 */
internal fun askOf(answer: String): MemberAsk? {
    val lines = answer.lines()
    val opening = lines.indexOfLast { it.trim() == TeamText.ASK_OPENING }
    if (opening < 0) return null
    val after = lines.drop(opening + 1)
    val closing = after.indexOfFirst { it.trim() == TeamText.BLOCK_CLOSING }
    if (closing < 0) return null
    val json = runCatching { Json.parseToJsonElement(after.take(closing).joinToString("\n")) }.getOrNull()
    val questions = questionsOf(json).take(MAX_QUESTIONS)
    if (questions.isEmpty()) return null
    return MemberAsk(TeamText.withoutBlocks(answer), questions)
}

private fun questionsOf(json: JsonElement?): List<MemberQuestion> {
    val list = (json as? JsonObject)?.get(QUESTIONS) as? JsonArray ?: return emptyList()
    return list.mapNotNull { questionOf(it) }
}

private fun questionOf(entry: JsonElement): MemberQuestion? {
    val obj = entry as? JsonObject ?: return null
    val text = stringOf(obj[QUESTION])?.trim()
    if (text.isNullOrEmpty()) return null
    val options = (obj[OPTIONS] as? JsonArray).orEmpty().mapNotNull { stringOf(it)?.trim() }
    return MemberQuestion(text, options.filter { it.isNotEmpty() }.distinct())
}

private fun stringOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

/** What a member is told about asking the user: when to, and how to write its questions. */
internal fun askInstructions(): String =
    listOf(
        "# Asking the user",
        "You may ask the user questions before you work, when their answer changes what you do: the technical",
        "choices of a specification, the designs to propose, what is not settled. Ask only what you cannot decide",
        "yourself, in one message, then stop and wait: the user's answers come back in the next message. To ask, end",
        "your message with a fenced block tagged `ask` that holds JSON:",
        "",
        "${TeamText.ASK_OPENING}\n" +
            """{"questions": [{"question": "Which database?", "options": ["SQLite", "Postgres"]}]}""" +
            "\n${TeamText.BLOCK_CLOSING}",
        "",
        "- At most $MAX_QUESTIONS questions per message. `options` are the choices you propose (leave it out or empty",
        "  when the answer is free); the user may always answer in their own words.",
        "- When you need nothing from the user, do your task and end with your report, without an `ask` block.",
    ).joinToString("\n")

/** What the member is told once the user answered its questions. */
internal fun answersText(answered: List<AnsweredQuestion>): String =
    answered.joinToString("\n\n") { "Question: ${it.question.text}\nAnswer: ${it.answer}" }
