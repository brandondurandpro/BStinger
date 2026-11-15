package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolKind
import org.example.kotlineditor.model.AgentToolStatus

/**
 * A sub-agent the manager launched, as the transcript shows it: [name] is the member of the team it matches, else
 * its [description] (the title of the call); [result] is the report it gave back, empty while it works.
 */
data class AgentRun(
    val id: String,
    val name: String,
    val description: String,
    val status: AgentToolStatus,
    val result: String,
    val member: TeamMember?,
)

// What the agent calls "thinking" besides launching a sub-agent: not a run.
private val NOT_A_LAUNCH = listOf("update todos", "ready to code", "exit plan mode", "todo")

private fun isLaunch(call: AgentItem.ToolCall): Boolean {
    if (call.kind != AgentToolKind.Think) return false
    val title = call.title.trim().lowercase()
    return NOT_A_LAUNCH.none { title.startsWith(it) }
}

// The member whose name the call mentions (its title, else its report): the longest name wins.
private fun memberOf(call: AgentItem.ToolCall, team: AgentTeam?): TeamMember? {
    val candidates = team?.members.orEmpty().filter { it.name.isNotBlank() }
    val title = squashed(call.title)
    val report = squashed(call.output)
    val inTitle = candidates.filter { squashed(it.name) in title }
    val found = inTitle.ifEmpty { candidates.filter { squashed(it.name) in report } }
    return found.maxByOrNull { it.name.length }
}

/** The sub-agent runs of [items], in the order they were launched; [team] names the members they are matched to. */
fun agentRuns(items: List<AgentItem>, team: AgentTeam?): List<AgentRun> {
    val launches = items.filterIsInstance<AgentItem.ToolCall>().filter { isLaunch(it) }
    return launches.map { call ->
        val member = memberOf(call, team)
        val description = call.title.trim()
        AgentRun(call.id, member?.name ?: description, description, call.status, call.output.trim(), member)
    }
}

/** The tool calls of the manager itself: all but the launches of sub-agents. */
fun managerCalls(items: List<AgentItem>): List<AgentItem.ToolCall> =
    items.filterIsInstance<AgentItem.ToolCall>().filterNot { isLaunch(it) }
