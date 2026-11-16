package org.example.kotlineditor.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent

/** One task a manager gave: what [member] must do. */
data class MemberTask(
    /** Who must do it. */
    val member: TeamAgent,
    /** What it must do. */
    val task: String,
)

/** What a manager's answer asks of the harness. */
sealed interface ManagerReply {
    /** The answer asks for nothing: the run is over. [message] is the answer. */
    data class Answer(
        /** What the manager said. */
        val message: String,
    ) : ManagerReply

    /** The answer delegates [tasks], in the order written; [message] is the answer without the delegation. */
    data class Delegate(val message: String, val tasks: List<MemberTask>) : ManagerReply

    /** The answer tried to delegate and could not be understood: [problem] is what the manager is told. */
    data class Invalid(
        /** What the manager said, without the block. */
        val message: String,
        /** Why the block cannot be run. */
        val problem: String,
    ) : ManagerReply
}

/**
 * How a manager delegates to its members. The harness only knows this interface, so that a tool of the agent (MCP)
 * can replace the text protocol of [TextDelegationProtocol] without touching the run loop.
 */
interface DelegationProtocol {
    /** What the manager of [team] is told about delegating: how to hand tasks to members and what comes back. */
    fun instructions(team: Team): String

    /** What the [answer] of the manager of [team] asks. */
    fun parse(answer: String, team: Team): ManagerReply
}

/**
 * Delegation in text, which works with any agent: the manager ends a message with a fenced block
 *
 * ````
 * ```team
 * {"delegate": [{"member": "Backend", "task": "…"}, {"member": "Tester", "task": "…"}]}
 * ```
 * ````
 *
 * The last `team` block of the answer wins; the text around it is the manager's message.
 */
class TextDelegationProtocol : DelegationProtocol {
    override fun instructions(team: Team): String {
        val names = team.members.joinToString(", ") { it.name }
        val example = """{"delegate": [{"member": "${team.members.firstOrNull()?.name.orEmpty()}", "task": "..."}]}"""
        return listOf(
            "To hand tasks to your members, end your message with a fenced block tagged `team` that holds JSON:",
            "",
            "$OPENING\n$example\n$CLOSING",
            "",
            "- `member` is the name of a member, one of: $names.",
            "- `task` is everything the member needs: members do not see this conversation nor each other's work.",
            "- The tasks of one block run at the same time, except those of one same member, which run one after",
            "  the other. Each member reports back to you when it is done, and you may then delegate again.",
            "- When the work is done, or when you need nothing from your members, answer without a `team` block.",
            "- Only the last `team` block of a message counts.",
        ).joinToString("\n")
    }

    override fun parse(answer: String, team: Team): ManagerReply {
        val block = lastBlock(answer)
        return if (block == null) ManagerReply.Answer(answer.trim()) else parseBlock(answer, block, team)
    }

    // What the last block [block] of [answer] asks: its tasks, or why it cannot be run.
    private fun parseBlock(answer: String, block: Block, team: Team): ManagerReply {
        val message =
            answer
                .lines()
                .filterIndexed { index, _ -> index !in block.lines }
                .joinToString("\n")
                .trim()
        val json = if (block.closed) runCatching { Json.parseToJsonElement(block.text) }.getOrNull() else null
        return when {
            !block.closed -> ManagerReply.Invalid(message, "The `team` block is not closed by a line with ```.")
            json == null -> ManagerReply.Invalid(message, "The `team` block is not valid JSON.")
            else -> tasksOf(json, team, message)
        }
    }

    // The lines of the last `team` block of [answer], the text between its fences, and whether it is closed.
    private fun lastBlock(answer: String): Block? {
        val lines = answer.lines()
        val opening = lines.indexOfLast { it.trim() == OPENING }
        if (opening < 0) return null
        val after = lines.drop(opening + 1)
        val closing = after.indexOfFirst { it.trim() == CLOSING }
        val closed = closing >= 0
        val content = if (closed) after.take(closing) else after
        val end = if (closed) opening + 1 + closing else lines.lastIndex
        return Block((opening..end).toSet(), content.joinToString("\n"), closed)
    }

    private fun tasksOf(json: JsonElement, team: Team, message: String): ManagerReply {
        val list = (json as? JsonObject)?.get(DELEGATE) as? JsonArray
        return try {
            val entries = list ?: throw BadBlock("""The `team` block must be {"delegate": [...]}.""")
            if (entries.isEmpty()) throw BadBlock("The `delegate` list of the `team` block is empty.")
            ManagerReply.Delegate(message, entries.map { taskOf(it, team) })
        } catch (bad: BadBlock) {
            ManagerReply.Invalid(message, bad.problem)
        }
    }

    // The task [entry] describes; fails with the problem when it does not name a member of [team] and a task.
    private fun taskOf(entry: JsonElement, team: Team): MemberTask {
        val obj = entry as? JsonObject
        val name = (obj?.get(MEMBER) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        val text = (obj?.get(TASK) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (name.isNullOrEmpty() || text.isNullOrEmpty()) {
            throw BadBlock("Every entry of `delegate` needs a `member` and a `task`, both text.")
        }
        val exact = team.members.firstOrNull { it.name == name }
        val member = exact ?: team.members.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (member == null) {
            val names = team.members.joinToString(", ") { it.name }
            throw BadBlock("""There is no member named "$name". The members of the team are: $names.""")
        }
        return MemberTask(member, text)
    }

    // A block that cannot be run, for the reason [problem].
    private class BadBlock(val problem: String) : Exception(problem)

    // A `team` block: the indexes of its lines (fences included), its text, whether its closing fence is there.
    private class Block(val lines: Set<Int>, val text: String, val closed: Boolean)

    private companion object {
        const val OPENING = "```team"
        const val CLOSING = "```"
        const val DELEGATE = "delegate"
        const val MEMBER = "member"
        const val TASK = "task"
    }
}
