package org.example.kotlineditor.harness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.kotlineditor.acp.AgentService
import org.example.kotlineditor.acp.ConversationLease
import org.example.kotlineditor.acp.StopCause
import org.example.kotlineditor.acp.TurnEnding
import org.example.kotlineditor.acp.TurnOutcome
import org.example.kotlineditor.model.AGENT_BUSY_MESSAGE
import org.example.kotlineditor.model.AgentAttachment
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentSkill
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/** Limits of the harness. */
data class HarnessSettings(
    /** How many rounds (a delegation, or an answer to a delegation that could not be run) a run goes through. */
    val maxRounds: Int = MAX_ROUNDS,
    /** How many prompts of the user wait for the run in progress; beyond it the prompt is refused. */
    val queuedPrompts: Int = QUEUED_PROMPTS,
) {
    /** The defaults. */
    companion object {
        /** A run stops after this many rounds, with an error notice. */
        const val MAX_ROUNDS = 20

        /** Like the prompts a plain conversation keeps while its agent answers. */
        const val QUEUED_PROMPTS = 4
    }
}

private const val STOPPED = "Stopped."

/**
 * The coordination of agent teams, on top of the public surface of [AgentService]. A team conversation `K` is the
 * manager's conversation; each member has its own conversation `K#<memberId>`, created at its first task. A prompt of
 * the user starts a run: the manager answers, the tasks it delegates go to its members in parallel, their reports go
 * back to it, and so on until it answers without delegating. The conversation shows busy for the whole run.
 *
 * Prompts of the user during a run wait here, up to [HarnessSettings.queuedPrompts]; each starts a run once the one
 * before it ended. [cancel] and [close] stop the whole team, members included. Everything kept for a conversation is
 * released by [close].
 */
@Suppress("TooManyFunctions") // the run loop of a team: prompt, rounds, members, stopping and closing
class TeamHarness(
    private val agents: AgentService,
    private val skills: SkillDiscovery,
    private val settings: HarnessSettings = HarnessSettings(),
    private val protocol: DelegationProtocol = TextDelegationProtocol(),
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prompts = TeamPrompts(protocol)
    private val conversations = ConcurrentHashMap<String, TeamConversation>()

    // The settings the user changed in each conversation, to give to the members of a team (its permission mode).
    private val chosen = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    /**
     * Sends the [text] of the user to the manager of [team] in [conversation], which works in [directory]; the first
     * prompt of a conversation locks its team and directory. Returns at once; the run goes on in the background. A
     * prompt sent twice (same [itemId]) runs once. During a run the prompt waits for its end; fails with
     * [AGENT_BUSY_MESSAGE] when too many wait already, the same refusal as a plain conversation's.
     */
    fun prompt(
        conversation: String,
        team: Team,
        directory: String,
        itemId: String,
        text: String,
        session: String? = null,
        attachments: List<AgentAttachment> = emptyList(),
    ) {
        val state = conversations.computeIfAbsent(conversation) { TeamConversation(conversation, team, directory) }
        val prompt = QueuedPrompt(itemId, text, session, attachments)
        val start =
            synchronized(state.lock) {
                if (itemId in state.seen) return
                if (state.running) {
                    check(state.queue.size < settings.queuedPrompts) { AGENT_BUSY_MESSAGE }
                    state.seen.add(itemId)
                    state.queue.addLast(prompt)
                    false
                } else {
                    state.seen.add(itemId)
                    state.running = true
                    true
                }
            }
        if (start) scope.launch { runPrompts(state, prompt) }
    }

    /** Whether [conversation] is a team conversation of this harness. */
    fun isTeam(conversation: String): Boolean = conversations.containsKey(conversation)

    /** Stops the run in [conversation], members included (their tasks end cancelled), like [AgentService.cancel]. */
    fun cancel(conversation: String) {
        val state = conversations[conversation]
        if (state != null) cancelMembers(state)
        agents.cancel(conversation)
    }

    /**
     * Forgets [conversation]: its run stops, its members' conversations are closed with it, and what the harness kept
     * of it is released. A conversation that is not a team's is simply closed.
     */
    suspend fun close(conversation: String) {
        chosen.remove(conversation)
        val state = conversations[conversation]
        if (state != null) {
            synchronized(state.lock) {
                state.closed = true
                state.queue.clear()
            }
            conversations.remove(conversation, state)
            closeSessions(state)
        }
        agents.close(conversation)
    }

    /**
     * Sets the setting [option] of [conversation] like [AgentService.setOption] and, when it is its permission mode,
     * gives the same value to the members whose agent offers it, now or when they start.
     */
    suspend fun setOption(conversation: String, agent: AgentCommand, option: String, value: String) {
        agents.setOption(conversation, agent, option, value)
        val shared = chosen.computeIfAbsent(conversation) { ConcurrentHashMap() }
        shared[option] = value
        val state = conversations[conversation] ?: return
        for (session in state.members.values) {
            if (session.configured) applyMode(state, session, option, value)
        }
    }

    /** How many conversations and chosen settings the harness keeps: none once every conversation is closed. */
    internal fun tracked(): Int = conversations.size + chosen.size

    override fun close() {
        scope.cancel()
        conversations.clear()
        chosen.clear()
    }

    // The runs of [state]: [first], then the prompts that waited, one after the other. The lease of the next run is
    // taken before the one of the run that ended is released, so that the transcript never shows idle between them.
    private suspend fun runPrompts(state: TeamConversation, first: QueuedPrompt) {
        var lease = agents.keepBusy(state.key)
        var ended = false
        try {
            var current: QueuedPrompt = first
            while (true) {
                runGuarded(state, current, lease)
                val next = nextQueued(state)
                if (next == null) {
                    ended = true
                    break
                }
                val following = agents.keepBusy(state.key)
                lease.release()
                lease = following
                current = next
            }
        } finally {
            lease.release()
            // Decided by nextQueued when the run ended by itself: a new run may have started since.
            if (!ended) synchronized(state.lock) { state.running = false }
            // A write that raced with the closing may have created the conversation again.
            if (state.closed && !conversations.containsKey(state.key)) {
                withContext(NonCancellable) {
                    closeSessions(state)
                    agents.close(state.key)
                }
            }
        }
    }

    // The next prompt that waited; when there is none the run is over (decided with the queue locked).
    private fun nextQueued(state: TeamConversation): QueuedPrompt? =
        synchronized(state.lock) {
            val next = state.queue.removeFirstOrNull()
            if (next == null) state.running = false
            next
        }

    // A run, whose failure ends it with a notice and the members stopped, never the loop of the queue.
    private suspend fun runGuarded(state: TeamConversation, prompt: QueuedPrompt, lease: ConversationLease) {
        attempt(
            onFailure = { failure ->
                cancelMembers(state)
                notice(state, "The team stopped: ${failure.reason()}", error = true)
            },
        ) { run(state, prompt, lease) }
    }

    private suspend fun run(state: TeamConversation, prompt: QueuedPrompt, lease: ConversationLease) {
        val first = !state.managerStarted
        val composed = managerFirstOrNext(state, prompt, first)
        val session = if (first) prompt.session else null
        val manager = state.team.manager
        val directory = state.directory
        agents.prompt(
            state.key,
            manager.agent,
            directory,
            prompt.itemId,
            composed,
            session,
            prompt.attachments,
            prompt.text,
            lease,
        )
        var itemId = prompt.itemId
        var rounds = 0
        while (true) {
            val next = afterTurn(state, lease, itemId, rounds) ?: break
            rounds++
            itemId = state.nextId("relay")
            agents.prompt(state.key, manager.agent, directory, itemId, next, shown = null, lease = lease)
        }
    }

    // Waits for the manager's turn [itemId] and tells what the manager is sent next (the reports of a round, or why its
    // block could not be run); null when the run is over: the manager answered, failed, or was stopped.
    private suspend fun afterTurn(
        state: TeamConversation,
        lease: ConversationLease,
        itemId: String,
        rounds: Int,
    ): String? {
        val outcome = agents.awaitTurn(state.key, itemId)
        val cause = stopCause(lease)
        return when {
            cause != null -> {
                stopped(state, cause, outcome)
                null
            }

            outcome.ending != TurnEnding.Completed -> {
                null
            }

            else -> {
                answerTo(state, lease, protocol.parse(outcome.text, state.team), rounds)
            }
        }
    }

    // What the manager is sent for [reply], its [rounds]-th answer; null when it asks for nothing more.
    private suspend fun answerTo(
        state: TeamConversation,
        lease: ConversationLease,
        reply: ManagerReply,
        rounds: Int,
    ): String? =
        when (reply) {
            is ManagerReply.Answer -> null
            is ManagerReply.Invalid -> if (outOfRounds(state, rounds)) null else prompts.refusal(reply.problem)
            is ManagerReply.Delegate -> if (outOfRounds(state, rounds)) null else reportsOf(state, lease, reply.tasks)
        }

    // Runs a round of [tasks]; what the manager is sent about it, null when the run was stopped meanwhile.
    private suspend fun reportsOf(state: TeamConversation, lease: ConversationLease, tasks: List<MemberTask>): String? {
        val reports = runRound(state, lease, tasks)
        val cause = stopCause(lease)
        if (cause != null) stopped(state, cause, null)
        return if (cause == null) prompts.relay(reports) else null
    }

    // The text of the first prompt of the manager of this run: with its preamble the first time, settings applied.
    private suspend fun managerFirstOrNext(state: TeamConversation, prompt: QueuedPrompt, first: Boolean): String {
        if (!first) return prompts.managerNext(prompt.text)
        state.managerStarted = true
        val manager = state.team.manager
        applySettings(state, state.key, manager)
        val own = skillsOf(state, manager)
        return prompts.managerFirst(state.team, own, prompt.text)
    }

    // True, with a notice, when the run went through all the rounds it may.
    private fun outOfRounds(state: TeamConversation, rounds: Int): Boolean {
        if (rounds < settings.maxRounds) return false
        val text = "The team stopped after ${settings.maxRounds} rounds without a final answer."
        notice(state, text, error = true)
        return true
    }

    // The run was stopped: the members stop too and nothing more goes to the manager. A cancel the manager's own turn
    // did not see (between two turns, or just after one) has no notice yet: it is written here.
    private fun stopped(state: TeamConversation, cause: StopCause, managerTurn: TurnOutcome?) {
        cancelMembers(state)
        val seen = managerTurn?.ending == TurnEnding.Cancelled
        if (cause == StopCause.Cancelled && !seen) notice(state, STOPPED, error = false)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun stopCause(lease: ConversationLease): StopCause? {
        val ended = lease.stopped
        return if (ended.isCompleted) ended.getCompleted() else null
    }

    // One round: a Delegation item per task, the members working in parallel (the tasks of one member in order); the
    // reports, in the order of the tasks. A stop cancels the members still working.
    private suspend fun runRound(state: TeamConversation, lease: ConversationLease, tasks: List<MemberTask>) =
        coroutineScope {
            val items = tasks.map { addDelegation(state, it) }
            val watcher =
                launch {
                    lease.stopped.await()
                    cancelMembers(state)
                }
            val byMember = tasks.indices.groupBy { tasks[it].member.id }
            val workers =
                byMember.values.map { indexes ->
                    async { indexes.map { index -> index to runTask(state, lease, tasks[index], items[index]) } }
                }
            val reports =
                workers
                    .awaitAll()
                    .flatten()
                    .sortedBy { it.first }
                    .map { it.second }
            watcher.cancel()
            reports
        }

    private fun addDelegation(state: TeamConversation, task: MemberTask): String {
        val key = state.memberKey(task.member)
        val name = task.member.name
        val running = DelegationStatus.Running
        val item = agents.addItem(state.key) { id -> AgentItem.Delegation(id, 0, name, key, task.task, running) }
        return item.id
    }

    // The task [task], whose item is [itemId]: the member does it, the item and the report tell how it ended.
    private suspend fun runTask(
        state: TeamConversation,
        lease: ConversationLease,
        task: MemberTask,
        itemId: String,
    ): MemberReport {
        val member = task.member
        val outcome =
            if (stopCause(lease) != null) {
                TurnOutcome(TurnEnding.Cancelled, "")
            } else {
                attempt(
                    onFailure = { TurnOutcome(TurnEnding.Failed, "", it.reason()) },
                ) { giveTask(state, lease, task) }
            }
        val status = statusOf(outcome)
        val failed = outcome.ending == TurnEnding.Failed
        val text = if (failed) outcome.reason.ifBlank { UNKNOWN_FAILURE } else outcome.text
        agents.updateItem(state.key, itemId) { item -> ended(item, status, text) }
        return MemberReport(member.name, task.task, reportStatusOf(outcome), text)
    }

    // The member does [task] in its own conversation, and the outcome of its turn is returned.
    private suspend fun giveTask(state: TeamConversation, lease: ConversationLease, task: MemberTask): TurnOutcome {
        val member = task.member
        val session = memberSession(state, member)
        val first = !session.prompted
        session.prompted = true
        val text =
            if (first) {
                prompts.memberFirst(state.team, member, skillsOf(state, member), task.task)
            } else {
                prompts.memberNext(state.team, task.task)
            }
        val promptId = state.nextId("task")
        agents.prompt(session.key, member.agent, state.directory, promptId, text, shown = task.task)
        // A stop that came while the prompt was being sent did not see the member run.
        if (stopCause(lease) != null) agents.cancel(session.key)
        val outcome = agents.awaitTurn(session.key, promptId)
        if (first) mirrorRefusals(state, session)
        return outcome
    }

    private suspend fun memberSession(state: TeamConversation, member: TeamAgent): MemberSession {
        val session = state.members.computeIfAbsent(member.id) { MemberSession(state.memberKey(member), member) }
        if (!session.configured) {
            session.configured = true
            applySettings(state, session.key, member)
            val modes = chosen[state.key].orEmpty()
            for ((option, value) in modes) applyMode(state, session, option, value)
        }
        return session
    }

    // The settings of [agent] set on its conversation [key] before its first prompt; one refused is told in the team's.
    private suspend fun applySettings(state: TeamConversation, key: String, agent: TeamAgent) {
        for ((option, value) in agent.settings) {
            attempt(
                onFailure = {
                    notice(
                        state,
                        "${agent.name}: cannot set $option to $value: ${it.reason()}",
                        error = true,
                    )
                },
            ) { agents.setOption(key, agent.agent, option, value) }
        }
    }

    // [option] is the permission mode: the member gets [value] when its agent offers it, else keeps its default.
    private suspend fun applyMode(state: TeamConversation, session: MemberSession, option: String, value: String) {
        val member = session.member
        val offered = attempt(onFailure = { emptyList() }) { agents.processes.options(member.agent, state.directory) }
        val setting = offered.firstOrNull { it.id == option && it.category == AgentOption.MODE }
        val offers = setting?.values?.any { it.value == value } == true
        if (offers) agents.setOption(session.key, member.agent, option, value)
    }

    // The settings a member's agent refused say so in the member's conversation; the team's shows them too.
    private suspend fun mirrorRefusals(state: TeamConversation, session: MemberSession) {
        val transcript = agents.transcript(session.key, agents.epoch, 0)
        val refusals =
            transcript.items
                .filterIsInstance<AgentItem.Notice>()
                .filter { it.error && it.text.startsWith(REFUSED) }
        for (refusal in refusals) notice(state, "${session.member.name}: ${refusal.text}", error = true)
    }

    // The skills of [agent] that still exist, as the prompt lists them; one that is gone is told, and skipped.
    private suspend fun skillsOf(state: TeamConversation, agent: TeamAgent): List<AgentSkill> {
        val found = withContext(Dispatchers.IO) { agent.skills.map { id -> id to skills.describe(id) } }
        for ((id, skill) in found) {
            if (skill == null) {
                notice(
                    state,
                    "${agent.name}: the skill $id does not exist any more, skipped.",
                    error = true,
                )
            }
        }
        return found.mapNotNull { it.second }
    }

    private fun cancelMembers(state: TeamConversation) {
        for (session in state.members.values) agents.cancel(session.key)
    }

    private suspend fun closeSessions(state: TeamConversation) {
        for (session in state.members.values) agents.close(session.key)
    }

    // A notice in the conversation of the manager, unless it is closed.
    private fun notice(state: TeamConversation, text: String, error: Boolean) {
        if (state.closed) return
        agents.addItem(state.key) { id -> AgentItem.Notice(id, 0, text, error) }
    }

    private companion object {
        const val REFUSED = "The agent refused"
        const val UNKNOWN_FAILURE = "The member failed."
    }
}

// The delegation [item] once its task ended as [status], with [text] as its result.
private fun ended(item: AgentItem, status: DelegationStatus, text: String): AgentItem {
    val delegation = item as? AgentItem.Delegation ?: return item
    return delegation.copy(status = status, result = text)
}

private fun statusOf(outcome: TurnOutcome): DelegationStatus =
    when (outcome.ending) {
        TurnEnding.Completed -> DelegationStatus.Done
        TurnEnding.Failed -> DelegationStatus.Failed
        TurnEnding.Cancelled -> DelegationStatus.Cancelled
    }

private fun reportStatusOf(outcome: TurnOutcome): ReportStatus =
    when (outcome.ending) {
        TurnEnding.Completed -> ReportStatus.Done
        TurnEnding.Failed -> ReportStatus.Failed
        TurnEnding.Cancelled -> ReportStatus.Cancelled
    }
