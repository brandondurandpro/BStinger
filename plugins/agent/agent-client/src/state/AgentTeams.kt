package org.example.kotlineditor.state

/**
 * A member of a team of agents: [name] says its role, [model] and [effort] are what it should run with (as the user
 * names them: `Opus 5.5`, `high`), [skills] the skills it may use and [prompt] its system prompt.
 */
data class TeamMember(
    val name: String,
    val model: String = "",
    val effort: String = "",
    val skills: List<String> = emptyList(),
    val prompt: String = "",
)

/**
 * A team of agents: the [manager] coordinates (its prompt says when to delegate), the [members] are the sub-agents
 * it delegates to and whose work it controls.
 */
data class AgentTeam(val name: String, val manager: TeamMember, val members: List<TeamMember> = emptyList())

private const val MANAGER = "manager"
private const val MEMBER = "member"
private const val FIELD_COUNT = 7
private const val SKILL_SEPARATOR = ","

private fun TeamMember.line(team: String, role: String): String =
    listOf(team, role, name, model, effort, skills.joinToString(SKILL_SEPARATOR), prompt).joinToString("\t") { escaped(it) }

/** The teams as saved: one member per line, the manager of each team first. */
internal fun encodeTeams(teams: List<AgentTeam>): String {
    val lines = mutableListOf<String>()
    for (team in teams) {
        lines.add(team.manager.line(team.name, MANAGER))
        team.members.forEach { lines.add(it.line(team.name, MEMBER)) }
    }
    return lines.joinToString("\n")
}

/** The teams saved by [encodeTeams]; a damaged line, a team without manager or a nameless member is ignored. */
internal fun decodeTeams(saved: String?): List<AgentTeam> {
    val managers = LinkedHashMap<String, TeamMember>()
    val members = HashMap<String, MutableList<TeamMember>>()
    for (line in saved.orEmpty().lines()) {
        val fields = line.split('\t').map { unescaped(it) }
        if (fields.size != FIELD_COUNT) continue
        val team = fields[0]
        val member = memberOf(fields)
        if (team.isBlank() || member.name.isBlank()) continue
        if (fields[1] == MANAGER) managers.putIfAbsent(team, member) else members.getOrPut(team) { mutableListOf() }.add(member)
    }
    return managers.map { (team, manager) -> AgentTeam(team, manager, members[team].orEmpty()) }
}

private fun memberOf(fields: List<String>): TeamMember {
    val skills = fields[5].split(SKILL_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    return TeamMember(fields[2].trim(), fields[3].trim(), fields[4].trim(), skills, fields[6].trim())
}

/** Skills as the user types them: separated by commas. */
internal fun parseSkills(text: String): List<String> = text.split(SKILL_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

/** The team created at first use: an Opus manager, a Sonnet developer, an Opus designer and a tester. */
internal fun defaultTeams(): List<AgentTeam> {
    val manager =
        TeamMember(
            name = "Manager",
            model = "Opus 5.5",
            effort = "high",
            prompt =
                "You coordinate the team and control its work; you do not write the code yourself unless the task is trivial. " +
                    "Split the request into small tasks, delegate each one to the member best suited to it " +
                    "(Dev for implementation, Designer for UI and UX, Tester for tests and verification), " +
                    "check what they bring back against the request, and send it back with precise remarks until it is right. " +
                    "Always have the Tester verify a change before you declare it done. Answer the user with a short summary.",
        )
    val developer =
        TeamMember(
            name = "Dev",
            model = "Sonnet 5.5",
            skills = listOf("simplify"),
            prompt =
                "You implement the task you are given, in the style of the code around it. Keep the change small and focused, " +
                    "decompose the logic into named steps, and report which files you changed and anything left undone.",
        )
    val designer =
        TeamMember(
            name = "Designer",
            model = "Opus 5.5",
            skills = listOf("artifact-design"),
            prompt =
                "You design and refine user interfaces: layout, spacing, colors, states and accessibility. Reuse the components " +
                    "the project already has before creating new ones, and describe the result as the user will see it.",
        )
    val tester =
        TeamMember(
            name = "Tester",
            model = "Sonnet 5.5",
            skills = listOf("run"),
            prompt =
                "You write and run tests. For each change, cover the main path and the edge cases through the real UI when the " +
                    "project has end-to-end tests, run them, and report what passes, what fails and why. Never weaken a test to make it pass.",
        )
    return listOf(AgentTeam("Default team", manager, listOf(developer, designer, tester)))
}

private fun TeamMember.description(): String {
    val details = mutableListOf<String>()
    if (model.isNotBlank()) details.add(model)
    if (effort.isNotBlank()) details.add("effort $effort")
    if (skills.isNotEmpty()) details.add("skills: ${skills.joinToString(", ")}")
    return if (details.isEmpty()) name else "$name (${details.joinToString(", ")})"
}

/**
 * What the manager is told before the first request of a conversation: its own system prompt, then each sub-agent
 * with its prompt, to hand to the agent it launches for it; the request of the user comes last.
 */
internal fun teamBriefing(team: AgentTeam, request: String): String {
    val out = StringBuilder()
    out.appendLine("You are ${team.manager.name}, the manager of the team \"${team.name}\".")
    if (team.manager.prompt.isNotBlank()) out.appendLine(team.manager.prompt)
    if (team.members.isNotEmpty()) {
        out.appendLine()
        out.appendLine("Your team. Delegate by launching a sub-agent for the member, with the member's instructions below as its brief")
        out.appendLine("(and its model when the tool lets you choose); then review what it returns before going on:")
        for (member in team.members) {
            out.appendLine()
            out.appendLine("## ${member.description()}")
            if (member.prompt.isNotBlank()) out.appendLine(member.prompt)
        }
    }
    out.appendLine()
    out.appendLine("# Request")
    out.append(request)
    return out.toString()
}

/** The value of [values] (`id` to name) the user's [wanted] words point at, comparing letters and digits only; null when none. */
internal fun matchingValue(values: List<Pair<String, String>>, wanted: String): String? {
    val key = wanted.filter { it.isLetterOrDigit() }.lowercase()
    if (key.isEmpty()) return null
    val exact = values.firstOrNull { (id, name) -> key == squashed(id) || key == squashed(name) }
    val partial = values.firstOrNull { (id, name) -> squashed(id).contains(key) || squashed(name).contains(key) }
    return (exact ?: partial)?.first
}

/** [text] with its letters and digits only, lower-cased: how the names the user types are compared. */
internal fun squashed(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()
