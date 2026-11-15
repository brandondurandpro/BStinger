package org.example.kotlineditor.acp.relay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.acp.AgentProcess
import org.example.kotlineditor.acp.AgentService
import org.example.kotlineditor.acp.Journal
import org.example.kotlineditor.acp.SessionExtras
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.model.RelayMember
import org.example.kotlineditor.model.RelayTeam
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The name of the MCP server of the relay, which Claude shows as `mcp__kotlin-editor-team__delegate`. */
internal const val RELAY_SERVER = "kotlin-editor-team"

private const val RAW_TASK_MARK = """

# Task
"""

// The conversation of the n-th delegation of the conversation `m` is `m~d<n>`.
private const val CHILD_MARK = "~d"

// The agent gives up a tool call after about a minute by default: a delegation may last much longer.
private const val TOOL_TIMEOUT_VARIABLE = "MCP_TOOL_TIMEOUT"
private const val TOOL_TIMEOUT_MS = "1800000"

/** This command with the environment a manager of the relay needs: its tool calls (the delegations) may last long. */
internal fun AgentCommand.forManager(): AgentCommand =
    copy(environment = environment + (TOOL_TIMEOUT_VARIABLE to TOOL_TIMEOUT_MS))

// What the conversation of a member says when its manager stopped waiting for it.
private const val GIVEN_UP =
    "The manager stopped waiting for this delegation (its call ended, a timeout or the end of its turn): " +
        "the member was stopped."

// Claude's own tools that launch sub-agents: disabled for a manager, whose team is the relay's.
private val NATIVE_LAUNCHERS = listOf("Task", "Agent")

/**
 * The IDE's server runs the team of a manager instead of the agent. The manager session gets an MCP tool,
 * `delegate(member, task)`, served by [McpEndpoint]; each delegation runs the member in its own conversation
 * (`<manager>~d<n>`, with its own session, model, effort and system prompt) and its last answer goes back to the
 * manager as the result of the tool. One delegation runs at a time. Cancelling or closing the manager does the same to
 * its delegations, and so does the end of its `delegate` call while the member works (the agent's tool timeout, the
 * end of its turn): nobody would read the report. The members never get the tool, so they cannot delegate in turn.
 */
internal class DelegationRelay(private val service: AgentService, private val scope: CoroutineScope) : Closeable {
    private val endpoint = lazy { McpEndpoint(scope) }
    private val managers = ConcurrentHashMap<String, Manager>()

    /**
     * Serves [team] to the manager conversation [parent] (running [agent] in [directory]), and returns what its session
     * must be started with. Called again, it keeps the token and changes the team.
     */
    fun attach(parent: String, agent: AgentCommand, directory: String, team: RelayTeam): SessionExtras {
        val manager = managers.computeIfAbsent(parent) { Manager(parent) }
        manager.serve(agent, directory, team)
        val access = manager.access ?: endpoint.value.register(manager).also { manager.access = it }
        return managerExtras(access)
    }

    /** Where the manager conversation [parent] reaches its tools; null when it is not served. */
    fun access(parent: String): McpAccess? = managers[parent]?.access

    /** Stops the delegations running for [parent]. */
    fun cancelChildren(parent: String) {
        managers[parent]?.cancelAll()
    }

    /** [parent] ends: its delegations are cancelled and closed, and its tools are no longer served. */
    suspend fun close(parent: String) {
        val manager = managers.remove(parent) ?: return
        manager.access?.let { endpoint.value.unregister(it.token) }
        manager.closeChildren()
    }

    override fun close() {
        if (endpoint.isInitialized()) endpoint.value.close()
        managers.clear()
    }

    // The manager conversation [parent], as the endpoint sees it.
    private inner class Manager(private val parent: String) : McpTools {
        @Volatile
        var access: McpAccess? = null

        @Volatile
        private var agent: AgentCommand = AgentCommand(emptyList())

        @Volatile
        private var directory: String = ""

        @Volatile
        private var team: RelayTeam = RelayTeam(RelayMember(""), emptyList())
        private val counter = AtomicInteger()
        private val turns = Mutex()

        // The conversations of the delegations, until the manager ends; the sessions already hidden from the history.
        private val delegated = ConcurrentHashMap.newKeySet<String>()
        private val hidden = ConcurrentHashMap.newKeySet<String>()

        fun serve(agent: AgentCommand, directory: String, team: RelayTeam) {
            this.agent = agent
            this.directory = directory
            this.team = team
        }

        fun cancelAll() {
            delegated.forEach(service::cancel)
        }

        suspend fun closeChildren() {
            cancelAll()
            delegated.forEach { service.close(it) }
            delegated.clear()
        }

        override fun members(): List<String> = team.members.map { it.name }

        override suspend fun delegate(member: String, task: String, toolUseId: String?): ToolOutcome {
            val spec = team.members.firstOrNull { it.name == member }
            if (spec == null) return ToolOutcome("There is no member named \"$member\".", isError = true)
            val child = "$parent$CHILD_MARK${counter.incrementAndGet()}"
            service.conversations[parent]?.attachChild(toolUseId, child, spec.name)
            delegated.add(child)
            try {
                return untilGivenUp(child) { turns.withLock { runMember(child, spec, task) } }
            } catch (cancelled: CancellationException) {
                service.cancel(child)
                throw cancelled
            }
        }

        // Runs [work], the delegation of [child], until the manager gives up its call: then it is cancelled, and the
        // conversation of the member says why.
        private suspend fun untilGivenUp(child: String, work: suspend () -> ToolOutcome): ToolOutcome =
            coroutineScope {
                val delegation = this
                val watch =
                    launch {
                        awaitGivenUp(child)
                        service.conversation(child).notice(GIVEN_UP)
                        delegation.cancel()
                    }
                try {
                    work()
                } finally {
                    watch.cancel()
                }
            }

        // Returns once the `delegate` call of the manager that runs [child] has ended; never when no call names it.
        private suspend fun awaitGivenUp(child: String) {
            val journal = service.conversations[parent]?.journal ?: awaitCancellation()
            journal.version.first { callEnded(journal, child) }
        }

        // The member's own conversation: its settings, then the task, then what it answered last.
        private suspend fun runMember(child: String, spec: RelayMember, task: String): ToolOutcome =
            try {
                val process = service.processes.processFor(agent)
                val prompt = brief(child, spec, task, process)
                // Set once its session lists its settings: the efforts offered depend on the model.
                val wanted = listOf(AgentOption.MODEL to spec.model, AgentOption.THOUGHT_LEVEL to spec.effort)
                service.conversation(child).keeper.byCategory = wanted.filter { it.second.isNotBlank() }
                service.prompt(child, agent, directory, "$child-task", prompt)
                val journal = service.conversation(child).journal
                journal.version.first { hideSession(journal, process) && journal.state == AgentTurnState.Idle }
                reportOf(journal)
            } catch (failure: IOException) {
                ToolOutcome("The delegation failed: ${failure.message}", isError = true)
            }

        // The text of the task. The Claude adapter takes the member's instructions as the system prompt of its session
        // (`_meta.systemPrompt.append`); another agent would ignore it, so they go in front of the task instead.
        private suspend fun brief(child: String, spec: RelayMember, task: String, process: AgentProcess): String {
            val name = process.ready().name.orEmpty()
            val claude = name.contains("claude", ignoreCase = true)
            val hasBrief = spec.prompt.isNotBlank()
            val conversation = service.conversation(child)
            if (claude && hasBrief) conversation.keeper.extras = SessionExtras(meta = systemPromptMeta(spec.prompt))
            val inTask = !claude && hasBrief
            return if (inTask) spec.prompt + RAW_TASK_MARK + task else task
        }

        // The session of a delegation is no conversation of the user: the history does not list it.
        private fun hideSession(journal: Journal, process: AgentProcess): Boolean {
            val session = journal.header.session
            if (session != null && hidden.add(session)) process.probes.keep(session)
            return true
        }
    }
}

// The manager gets the tool, and loses the launchers of its agent.
private fun managerExtras(access: McpAccess): SessionExtras {
    val server =
        buildJsonObject {
            put("type", "http")
            put("name", RELAY_SERVER)
            put("url", access.url)
            putJsonArray("headers") {
                add(
                    buildJsonObject {
                        put("name", "Authorization")
                        put("value", "Bearer ${access.token}")
                    },
                )
            }
        }
    val meta =
        buildJsonObject {
            putJsonObject("claudeCode") {
                putJsonObject("options") { put("disallowedTools", JsonArray(NATIVE_LAUNCHERS.map(::JsonPrimitive))) }
            }
        }
    return SessionExtras(listOf(server), meta)
}

private fun systemPromptMeta(prompt: String): JsonObject =
    buildJsonObject { putJsonObject("systemPrompt") { put("append", prompt) } }

// What the member answered last; why it did not, when it did not.
private fun reportOf(journal: Journal): ToolOutcome {
    val answers = journal.filter { it as? AgentItem.AgentText }
    val reply =
        answers
            .lastOrNull()
            ?.text
            ?.trim()
            .orEmpty()
    if (reply.isNotEmpty()) return ToolOutcome(reply)
    val notices = journal.filter { it as? AgentItem.Notice }.joinToString("\n") { it.text }
    return ToolOutcome(notices.ifEmpty { "The member sent no report." }, isError = true)
}

// Whether the call of the manager that runs [child] has ended: the manager no longer waits for its result.
private fun callEnded(journal: Journal, child: String): Boolean {
    val calls = journal.filter { it as? AgentItem.ToolCall }
    val call = calls.firstOrNull { it.child == child } ?: return false
    val waiting = call.status == AgentToolStatus.Pending || call.status == AgentToolStatus.Running
    return !waiting
}
