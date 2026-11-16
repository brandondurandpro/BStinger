package org.example.kotlineditor.harness

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.kotlineditor.acp.AgentService
import org.example.kotlineditor.acp.AgentSettings
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentTranscript
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import org.example.kotlineditor.testkit.acp.FakeAgentCommand
import org.example.kotlineditor.testkit.acp.FakeManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

const val TIMEOUT_MS = 30_000L

/** What a client following a conversation saw: its items, the state it saw at each change, and the last answer. */
class Followed(val items: List<AgentItem>, val states: List<Pair<AgentTurnState, Boolean>>, val last: AgentTranscript)

/** The texts the agent answered, in order. */
fun List<AgentItem>.answers(): List<String> = filterIsInstance<AgentItem.AgentText>().map { it.text }

/**
 * Follows the transcript of [conversation] like the IDE, from the start, until [done] holds. Each state seen is kept
 * with whether [done] held on the items at that moment.
 */
fun AgentService.follow(conversation: String, done: (AgentTurnState, List<AgentItem>) -> Boolean): Followed =
    runBlocking {
        withTimeout(TIMEOUT_MS) {
            val items = LinkedHashMap<String, AgentItem>()
            val states = ArrayList<Pair<AgentTurnState, Boolean>>()
            var since = 0L
            var last: AgentTranscript? = null
            var finished = false
            while (!finished) {
                val transcript = transcript(conversation, epoch, since)
                transcript.items.forEach { items[it.id] = it }
                since = transcript.version
                last = transcript
                finished = done(transcript.state, items.values.toList())
                states.add(transcript.state to finished)
            }
            Followed(items.values.toList(), states, checkNotNull(last))
        }
    }

/** Whether the transcript of [conversation] is known to the service, answered after the hold delay at most. */
fun AgentService.knows(conversation: String): Boolean = runBlocking { transcript(conversation, epoch, 0).known }

/** The prompts a fake agent received, from the sessions it kept in [store], in the order of the sessions. */
fun promptsIn(store: Path): List<String> {
    val files =
        store
            .toFile()
            .listFiles { file -> file.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.name }
    return files.flatMap { file -> turnsOf(file) }
}

private fun turnsOf(file: File): List<String> {
    val json = Json.parseToJsonElement(file.readText()).jsonObject
    val turns = json["turns"]?.jsonArray ?: JsonArray(emptyList())
    return turns.map {
        it.jsonObject["prompt"]
            ?.jsonPrimitive
            ?.content
            .orEmpty()
    }
}

/** The mode and the model a fake agent kept for its session in [store]. */
fun settingsIn(store: Path, field: String): List<String> {
    val files =
        store
            .toFile()
            .listFiles { file -> file.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.name }
    return files.map { file ->
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        json[field]?.jsonPrimitive?.content.orEmpty()
    }
}

/** A service, a harness and a team of fake agents, each one in its own process with its own store of sessions. */
class TeamFixture(
    harnessSettings: HarnessSettings = HarnessSettings(),
    protocol: DelegationProtocol = TextDelegationProtocol(),
    val dataDirectory: Path = Files.createTempDirectory("team-data"),
) : AutoCloseable {
    val service = AgentService(settings = AgentSettings(holdMs = 2_000, cancelGraceMs = 1_000))
    val project: Path = Files.createTempDirectory("team-project")
    val skills = SkillDiscovery(dataDirectory.toFile())
    val harness = TeamHarness(service, skills, harnessSettings, protocol)
    private val stores = HashMap<String, Path>()
    private var sent = 0

    /** The directory where the agent called [name] keeps its sessions. */
    fun storeOf(name: String): Path = stores.getOrPut(name) { Files.createTempDirectory("team-store-$name") }

    /** An agent of a team, its own fake agent process (a crash of one does not reach the others) unless [shared]. */
    fun agent(
        name: String,
        settings: Map<String, String> = emptyMap(),
        skills: List<String> = emptyList(),
        systemPrompt: String = "",
        description: String = "does $name things",
        shared: Boolean = false,
    ): TeamAgent {
        val own = FakeAgentCommand.command("--store", storeOf(name).toString())
        val command = AgentCommand(if (shared) FakeAgentCommand.command() else own)
        return TeamAgent("id-$name", name, description, command, settings, skills, systemPrompt)
    }

    /** A team of a manager and its members. */
    fun team(manager: TeamAgent, vararg members: TeamAgent) = Team("team-1", "Crew", manager, members.toList())

    /** Sends [text] to the manager of [team] in [conversation]; returns the id of the prompt. */
    fun send(team: Team, text: String, conversation: String = "K", itemId: String = "u${++sent}"): String {
        harness.prompt(conversation, team, project.toString(), itemId, text)
        return itemId
    }

    /** Follows [conversation] until it is idle and [until] holds. */
    fun idle(conversation: String = "K", until: (List<AgentItem>) -> Boolean): Followed =
        service.follow(conversation) { state, items -> state == AgentTurnState.Idle && until(items) }

    /** Follows [conversation] until [until] holds, whatever its state. */
    fun until(conversation: String, until: (List<AgentItem>) -> Boolean): Followed =
        service.follow(conversation) { _, items -> until(items) }

    /** Follows the run to the manager's final answer, and its idle state. */
    fun finished(conversation: String = "K"): Followed =
        idle(conversation) { items -> items.answers().any { it.startsWith(FakeManager.FINAL_PREFIX) } }

    /** The key of the conversation of [member]. */
    fun memberKey(member: TeamAgent, conversation: String = "K") = "$conversation#${member.id}"

    override fun close() {
        harness.close()
        service.close()
        dataDirectory.toFile().deleteRecursively()
        project.toFile().deleteRecursively()
        stores.values.forEach { it.toFile().deleteRecursively() }
    }
}

/** The delegation items of [this], in order. */
fun List<AgentItem>.delegations(): List<AgentItem.Delegation> = filterIsInstance<AgentItem.Delegation>()

/** The notices of [this], in order. */
fun List<AgentItem>.notices(): List<AgentItem.Notice> = filterIsInstance<AgentItem.Notice>()
