package org.example.kotlineditor.harness

import org.example.kotlineditor.model.AgentSkill
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent

private const val REPORT_TASK_CHARS = 120

/** How a member's work ended, as the manager is told. */
internal enum class ReportStatus(val word: String) {
    Done("done"),
    Failed("failed"),
    Cancelled("cancelled"),
}

/** What a member came back with for the task [task]: its report, or why it failed. */
internal class MemberReport(val member: String, val task: String, val status: ReportStatus, val text: String)

/**
 * The prompts the harness sends. Each one is a short introduction, then a last section headed [REQUEST_HEADING] that
 * holds what the agent has to act on (the text of the user, the task of a member, the reports of the members): an
 * agent reads it as the request, and the scripted agent of the tests finds its directive there.
 */
internal class TeamPrompts(private val protocol: DelegationProtocol) {
    /** The first prompt of the manager: who it is, its team, how to delegate, its instructions, then [request]. */
    fun managerFirst(team: Team, skills: List<AgentSkill>, request: String): String {
        val manager = team.manager
        val sections = ArrayList<String>()
        val role = descriptionOf(manager)
        sections.add("""You are ${manager.name}, the manager of the team "${team.name}".$role""")
        sections.add(
            "You coordinate the work of your team and the user only talks to you. Your members work in the same " +
                "directory as you, at the same time: hand them the tasks they can do, and answer the user yourself " +
                "once the work is done.",
        )
        sections.add("# Your team\n" + team.members.joinToString("\n") { memberLine(it) })
        sections.add("# How to delegate\n" + protocol.instructions(team))
        sections.addAll(ownSections(manager, skills))
        return wrap(sections.joinToString("\n\n"), request)
    }

    /** A later prompt of the manager: the user's [request]. */
    fun managerNext(request: String): String = wrap("", request)

    /** What the manager is told once its members ended a round: their [reports]. */
    fun relay(reports: List<MemberReport>): String {
        val intro =
            "Your members finished the tasks of this round. Their reports are in the last section of this " +
                "message. If the work needs more, delegate it in a new `team` block; if it is done, answer the " +
                "user without a `team` block."
        return wrap(intro, reports.joinToString("\n\n") { reportText(it) })
    }

    /** What the manager is told when its answer could not be run as a delegation, for [problem]. */
    fun refusal(problem: String): String {
        val intro =
            "Your last message could not be run as a delegation. The last section of this message says why. " +
                "Send a corrected `team` block, or answer the user without one."
        return wrap(intro, problem)
    }

    /** The first prompt of a member: its role in [team], how it works, its instructions, then the [task]. */
    fun memberFirst(team: Team, member: TeamAgent, skills: List<AgentSkill>, task: String): String {
        val manager = team.manager
        val sections = ArrayList<String>()
        sections.add("""You are ${member.name}, a member of the team "${team.name}".${descriptionOf(member)}""")
        sections.add(
            "Your manager, ${manager.name}, hands you tasks; your task is in the last section of this message. " +
                "Other members work in the same directory as you, at the same time: touch only what your task " +
                "covers. End with a short report of what you did and found: your manager receives it.",
        )
        sections.addAll(ownSections(member, skills))
        return wrap(sections.joinToString("\n\n"), task)
    }

    /** A later prompt of a member: a new [task] of its manager. */
    fun memberNext(team: Team, task: String): String {
        val intro = "${team.manager.name} gave you a new task. It is in the last section of this message."
        return wrap(intro, task)
    }

    private fun reportText(report: MemberReport): String {
        val task = oneLine(report.task).take(REPORT_TASK_CHARS)
        val text = report.text.ifBlank { "(nothing reported)" }
        val heading = "## ${report.member} (${report.status.word})"
        return listOf(heading, "Task: $task", "", text).joinToString("\n")
    }
}
