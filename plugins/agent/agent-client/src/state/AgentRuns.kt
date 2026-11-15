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
    /** The key of the conversation of the member on the server when the IDE ran it (relay mode); null otherwise. */
    val child: String? = null,
    /** True for a delegation of the IDE's relay (its session may not be known yet), false for a launch of the agent. */
    val relayed: Boolean = false,
)

// What the agent calls "thinking" besides launching a sub-agent: not a run.
private val NOT_A_LAUNCH = listOf("update todos", "ready to code", "exit plan mode", "todo")

// The names the adapter gives the tool that launches a sub-agent.
private val LAUNCH_TOOLS = listOf("Agent", "Task")

// The tool the IDE gives a manager in relay mode (`mcp__<server>__delegate` for Claude).
private fun isDelegate(call: AgentItem.ToolCall): Boolean {
    val tool = call.tool.orEmpty()
    return call.child != null || tool.endsWith("delegate", ignoreCase = true)
}

private fun isLaunch(call: AgentItem.ToolCall): Boolean {
    if (isDelegate(call)) return true
    val tool = call.tool
    if (tool != null) return tool in LAUNCH_TOOLS
    if (call.kind != AgentToolKind.Think) return false
    val title = call.title.trim().lowercase()
    return NOT_A_LAUNCH.none { title.startsWith(it) }
}

// The member whose name the call mentions (its title, else its report): the longest name wins.
private fun memberOf(call: AgentItem.ToolCall, team: AgentTeam?): TeamMember? {
    val candidates = team?.members.orEmpty().filter { it.name.isNotBlank() }
    // A delegation of the relay is titled `delegate · Member`: the member is what follows the dot.
    val title = squashed(call.title.substringAfterLast('·'))
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
        AgentRun(call.id, member?.name ?: description, description, call.status, call.output.trim(), member, call.child, isDelegate(call))
    }
}

/**
 * The tool calls of the manager itself: all but the launches of sub-agents and the calls the sub-agents made; in
 * [relay] mode also the `ToolSearch` calls the agent makes to find the `delegate` tool.
 */
fun managerCalls(items: List<AgentItem>, relay: Boolean = false): List<AgentItem.ToolCall> {
    val calls = items.filterIsInstance<AgentItem.ToolCall>()
    return calls.filter { !isLaunch(it) && it.parent == null && !(relay && it.tool == "ToolSearch") }
}

/** The tool calls [run] made, in order; empty when the agent does not tell (a reloaded conversation). */
fun nestedCalls(items: List<AgentItem>, run: AgentRun): List<AgentItem.ToolCall> =
    items.filterIsInstance<AgentItem.ToolCall>().filter { it.parent == run.id }
