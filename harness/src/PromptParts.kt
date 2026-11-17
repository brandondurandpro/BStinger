package org.example.kotlineditor.harness

import org.example.kotlineditor.model.AgentSkill
import org.example.kotlineditor.model.TeamAgent
import org.example.kotlineditor.model.TeamText

// " <description>" of an agent, empty when it has none.
internal fun descriptionOf(agent: TeamAgent): String {
    val text = agent.description.trim()
    return if (text.isEmpty()) "" else " $text"
}

// The line that tells a manager about [member].
internal fun memberLine(member: TeamAgent): String {
    val text = member.description.trim()
    val about = if (text.isEmpty()) "" else ": ${oneLine(text)}"
    return "- ${member.name}$about"
}

// The sections of an agent's own instructions and skills; none when it has neither.
internal fun ownSections(agent: TeamAgent, skills: List<AgentSkill>): List<String> {
    val sections = ArrayList<String>()
    val instructions = agent.systemPrompt.trim()
    if (instructions.isNotEmpty()) sections.add("# Your instructions\n$instructions")
    if (skills.isNotEmpty()) {
        val header = "# Your skills\nRead the SKILL.md of a skill before you use it."
        sections.add(header + "\n" + skills.joinToString("\n") { skillLine(it) })
    }
    return sections
}

// A skill as one line: its name, what it is for, and the file to read before using it.
private fun skillLine(skill: AgentSkill): String {
    val about = if (skill.description.isBlank()) "" else ": ${oneLine(skill.description)}"
    val folder = skill.id.trimEnd('/', '\\')
    return "- ${skill.name}$about ($folder/$SKILL_FILE)"
}

/** [text] on one line. */
internal fun oneLine(text: String): String =
    text
        .lines()
        .joinToString(" ") { it.trim() }
        .trim()

// [intro] (when there is one), then the last section: [request]. The IDE reads these prompts back when an agent
// replays its session, so their shape is the one [TeamText] defines.
internal fun wrap(intro: String, request: String): String = TeamText.wrap(intro, request)
