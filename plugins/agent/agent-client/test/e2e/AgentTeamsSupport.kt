package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import org.example.kotlineditor.rpc.teamsToText
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.testkit.acp.FakeRegistry
import org.example.kotlineditor.ui.AgentTags

/** The agent of the teams of the tests: the one the fake registry serves, which offers models and efforts. */
const val TEAM_PROVIDER = "fake-agent"

/** Preferences with agent mode on and the registry agent [TEAM_PROVIDER] as the agent of the settings, plus [more]. */
fun teamPreferences(vararg more: Pair<String, String>) =
    InMemoryPreferenceStore(
        mapOf(
            PreferenceKeys.AGENT_MODE to "true",
            PreferenceKeys.AGENT_REGISTRY to TEAM_PROVIDER,
        ) + more,
    )

/** A team of tests: a manager and [members] members, all on [TEAM_PROVIDER]. */
fun sampleTeam(name: String, members: Int = 1, id: String = "team-$name"): Team {
    fun agent(agentName: String) =
        TeamAgent(
            id = "$id-$agentName",
            name = agentName,
            description = "$agentName of $name",
            agent = AgentCommand(emptyList(), registry = TEAM_PROVIDER),
        )
    return Team(id, name, agent("Lead"), (1..members).map { agent("Member $it") })
}

/** The preferences key and value that hold [teams] for every project. */
fun globalTeams(vararg teams: Team): Pair<String, String> = PreferenceKeys.AGENT_TEAMS to teams.toList().teamsToText()

/** Opens the Teams window from the conversation's bar. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openTeams() {
    awaitTag(AgentTags.AGENT_TEAMS, timeout = AGENT_TIMEOUT).performClick()
    awaitTag(AgentTags.AGENT_TEAMS_DIALOG)
}

/** Starts the form of a new team named [name]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.newTeam(name: String) {
    awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
    awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput(name)
}

/** The field [field] of the agent of [role], its tab shown and the field scrolled into the view of the form. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitField(role: String, field: String, timeout: Long = TIMEOUT): SemanticsNodeInteraction {
    showAgent(role)
    return awaitTag(AgentTags.agentTeamField(role, field), timeout).performScrollTo()
}

/** Shows the agent of [role] in the form: the agents are tabs, one shown at a time. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.showAgent(role: String) {
    awaitTag(AgentTags.agentTeamTab(role)).performScrollTo().performClick()
}

/** Types [text] in the field [field] of the agent of [role], replacing what it holds. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.fillAgent(role: String, field: String, text: String) {
    awaitField(role, field).performTextReplacement(text)
}

/**
 * Chooses [value] in the menu of the field [field] (`model`, `effort`, `provider`) of the agent of [role], once the
 * server said what it offers.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.chooseInAgent(role: String, field: String, value: String) {
    awaitField(role, field, AGENT_TIMEOUT).performClick()
    awaitTag(AgentTags.agentTeamValue(role, field, value)).performClick()
    awaitNoTag(AgentTags.agentTeamValue(role, field, value))
}

/** Waits until the button [field] of the agent of [role] shows [name] (`Smart`). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitShownChoice(role: String, field: String, name: String) =
    awaitOption(AgentTags.agentTeamField(role, field), name)

/** Saves the form, which closes it. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.saveTeam() {
    awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
    awaitNoTag(AgentTags.AGENT_TEAM_FORM)
}

/** The fake registry and a data directory for a server that reads it. */
class TeamRegistry : AutoCloseable {
    val registry = FakeRegistry(org.example.kotlineditor.acp.registryPlatformForTests())
    val data: java.nio.file.Path = java.nio.file.Files.createTempDirectory("agent-data")

    fun settings() = agentSettings(registry.url, data.toFile())

    /** The same, the skill catalog downloading from [repositories], listing [sources]. */
    fun settings(repositories: org.example.kotlineditor.testkit.skills.SkillRepositories, sources: List<String>) =
        agentSettings(registry.url, data.toFile(), repositories.archiveUrl, sources)

    override fun close() {
        registry.close()
        data.toFile().deleteRecursively()
    }
}

/**
 * A team whose agents are the fake agent started from a command line, which keeps its sessions under [store]: a
 * manager `Lead` and the [members]. They share one process and one store, unless [separate]: then each agent has a
 * store (a folder of its name under [store]) and a process of its own, so that one that crashes takes no other down.
 */
fun commandTeam(
    name: String,
    store: java.nio.file.Path,
    vararg members: String,
    separate: Boolean = false,
    id: String = "team-$name",
    /** The names of the members allowed to ask the user questions. */
    asking: Set<String> = emptySet(),
): Team {
    fun agent(agentName: String): TeamAgent {
        val sessions = if (separate) store.resolve(agentName) else store
        val command = org.example.kotlineditor.testkit.acp.FakeAgentCommand.command("--store", "$sessions")
        val canAsk = agentName in asking
        return TeamAgent("$id-$agentName", agentName, "$agentName of $name", AgentCommand(command), canAsk = canAsk)
    }
    return Team(id, name, agent("Lead"), members.map { agent(it) })
}

/** Chooses the team [id] in the picker of the prompt bar, once the settings of the agent show. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.pickTeam(id: String) {
    awaitTag(AgentTags.AGENT_PERMISSIONS, timeout = AGENT_TIMEOUT)
    awaitTag(AgentTags.AGENT_TEAM_PICKER).performClick()
    awaitTag(AgentTags.agentTeamChoice(id)).performClick()
    awaitNoTag(AgentTags.AGENT_TEAM_PICKER_NONE)
}

/** The tasks the manager of [this] team conversation delegated, in order. */
fun org.example.kotlineditor.state.Conversation.delegations(): List<org.example.kotlineditor.model.AgentItem.Delegation> =
    items.filterIsInstance<org.example.kotlineditor.model.AgentItem.Delegation>()

/** The latest task given to the member named [member], as the conversation shows it now. */
fun org.example.kotlineditor.state.Conversation.delegationOf(member: String): org.example.kotlineditor.model.AgentItem.Delegation =
    checkNotNull(delegations().lastOrNull { it.member == member }) { "No task for $member in ${delegations()}" }

/** Waits until the manager of [conversation] delegated [count] tasks. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitDelegations(conversation: org.example.kotlineditor.state.Conversation, count: Int) {
    awaitCondition(AGENT_TIMEOUT) { conversation.delegations().size >= count }
}

/** Waits until the card of [delegation] says [status] (`running…`, `done`, `failed`, `cancelled`). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitDelegationStatus(delegation: org.example.kotlineditor.model.AgentItem.Delegation, status: String) {
    val tag = AgentTags.agentDelegationStatus(delegation.id)
    // In the header of the card, which is one button: its parts are nodes of the unmerged tree only.
    awaitTag(tag, timeout = AGENT_TIMEOUT, unmerged = true)
    try {
        awaitCondition(AGENT_TIMEOUT) { textOf(tag) == status }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("The card of ${delegation.member} says '${textOf(tag)}', not '$status'", e)
    }
}

/**
 * Waits until the manager of [conversation] gave its final answer and the run is over. Not [awaitIdle] alone: the
 * conversation is not busy yet between the prompt sent and the first news of the run.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitFinalAnswer(conversation: org.example.kotlineditor.state.Conversation, timeout: Long = AGENT_TIMEOUT) {
    awaitAnswer(conversation, org.example.kotlineditor.testkit.acp.FakeManager.FINAL_PREFIX, timeout)
    awaitIdle(conversation, timeout)
}

/** Clicks the option [option] of the permission the member named [member] asks for, at the end of the conversation. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.answerMember(member: String, option: String) {
    val request = androidx.compose.ui.test.hasTestTag(AgentTags.agentMemberRequest(member))
    val button = androidx.compose.ui.test.hasTestTag(AgentTags.agentPermission(option))
    awaitTag(AgentTags.agentMemberRequest(member), timeout = AGENT_TIMEOUT)
    onNode(button.and(androidx.compose.ui.test.hasAnyAncestor(request))).performClick()
}

/** Waits until the manager of [conversation] gave [count] final answers (one per run) and the last run is over. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitRuns(conversation: org.example.kotlineditor.state.Conversation, count: Int, timeout: Long = AGENT_TIMEOUT) {
    val prefix = org.example.kotlineditor.testkit.acp.FakeManager.FINAL_PREFIX
    try {
        awaitCondition(timeout) {
            val answers = conversation.items.filterIsInstance<org.example.kotlineditor.model.AgentItem.AgentText>()
            answers.count { it.text.startsWith(prefix) } >= count
        }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("Run $count did not end; the conversation shows:\n${shownConversation(conversation)}", e)
    }
    awaitIdle(conversation, timeout)
}

/** The questions the member [member] of [conversation] asked and the user did not answer yet, as it follows them. */
fun org.example.kotlineditor.state.Conversation.pendingQuestions(member: String): List<org.example.kotlineditor.model.AgentItem.Permission> {
    val key = delegationOf(member).conversation
    val items = members.transcripts[key]?.items.orEmpty()
    return items.filterIsInstance<org.example.kotlineditor.model.AgentItem.Permission>().filter { it.question && it.pending }
}

/** Waits until [count] questions of the member [member] wait for the user, and returns them in the order asked. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitQuestions(
    conversation: org.example.kotlineditor.state.Conversation,
    member: String,
    count: Int,
    timeout: Long = AGENT_TIMEOUT,
): List<org.example.kotlineditor.model.AgentItem.Permission> {
    awaitCondition(timeout) { conversation.delegations().any { it.member == member } }
    awaitCondition(timeout) { conversation.pendingQuestions(member).size == count }
    val asked = conversation.pendingQuestions(member)
    for (question in asked) awaitTag(AgentTags.agentRequestTitle(member, question.id), timeout = timeout)
    return asked
}

/** Answers [question] with the choice [choice] it proposed. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.answerChoice(member: String, question: org.example.kotlineditor.model.AgentItem.Permission, choice: String) {
    awaitTag(AgentTags.agentQuestionChoice(member, question.id, choice), timeout = AGENT_TIMEOUT).performClick()
}

/** Answers [question] in the user's own words. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.answerWords(member: String, question: org.example.kotlineditor.model.AgentItem.Permission, words: String) {
    awaitTag(AgentTags.agentQuestionText(member, question.id), timeout = AGENT_TIMEOUT).performTextInput(words)
    awaitTag(AgentTags.agentQuestionSend(member, question.id)).performClick()
}

/** Opens the skill catalog from the Teams window, opening that one first. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openCatalog() {
    openTeams()
    awaitTag(AgentTags.AGENT_SKILL_CATALOG).performClick()
    awaitTag(AgentTags.AGENT_CATALOG_DIALOG)
}
