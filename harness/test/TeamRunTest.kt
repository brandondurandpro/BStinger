package org.example.kotlineditor.harness

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.AGENT_BUSY_MESSAGE
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import org.example.kotlineditor.testkit.acp.FakeManager
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The run loop of a team, with the real service and fake agents: the manager, the members, the reports. */
class TeamRunTest {
    private var fixture = TeamFixture()
    private val lead get() = agentOf("Lead")
    private val members = HashMap<String, TeamAgent>()

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    // The agent called [name], the same one each time.
    private fun agentOf(name: String): TeamAgent = members.getOrPut(name) { fixture.agent(name) }

    private fun team(vararg names: String) = fixture.team(lead, *names.map(::agentOf).toTypedArray())

    private fun statuses(items: List<AgentItem>) = items.delegations().map { it.member to it.status }

    // The permission request of [key] still waiting, once there is one.
    private fun waitingPermission(key: String): AgentItem.Permission {
        val seen = fixture.until(key) { items -> items.any { it is AgentItem.Permission && it.chosen == null } }
        val request = seen.items.filterIsInstance<AgentItem.Permission>().firstOrNull { it.chosen == null }
        return requireNotNull(request)
    }

    private fun allow(key: String, request: AgentItem.Permission) = fixture.service.answer(key, request.id, "allow")

    private fun assertNoError(items: List<AgentItem>) {
        assertEquals(emptyList(), items.notices().filter { it.error }.map { it.text })
    }

    @Test
    fun `the manager delegates, the members report and answer, and the transcript shows what the user typed`() {
        val team = team("Backend", "Tester")
        fixture.send(team, "delegate Backend: echo built | Tester: echo checked")
        val seen = fixture.finished()
        assertEquals(listOf("delegate Backend: echo built | Tester: echo checked"), seen.items.userTexts())
        val items = seen.items.delegations()
        assertEquals(listOf("Backend", "Tester"), items.map { it.member })
        assertEquals(listOf(DelegationStatus.Done, DelegationStatus.Done), items.map { it.status })
        assertEquals(listOf("built", "checked"), items.map { it.result })
        assertEquals(listOf("K#id-Backend", "K#id-Tester"), items.map { it.conversation })
        val final =
            seen.items
                .answers()
                .lastOrNull()
                .orEmpty()
        assertContains(final, lines("## Backend (done)", "Task: echo built", "", "built"))
        assertContains(final, lines("## Tester (done)", "Task: echo checked", "", "checked"))
        assertNoError(seen.items)
    }

    @Test
    fun `two members work at the same time, not one after the other`() {
        val team = team("Backend", "Tester")
        val a = fixture.project.resolve("a.txt")
        val b = fixture.project.resolve("b.txt")
        fixture.send(team, "delegate Backend: write $a one | Tester: write $b two")
        // Each one waits for the user's answer before it writes: both must ask while the other still waits.
        val first = waitingPermission("K#id-Backend")
        val second = waitingPermission("K#id-Tester")
        assertFalse(a.toFile().exists() || b.toFile().exists())
        val running = fixture.until("K") { items -> items.delegations().size == 2 }.items.delegations()
        assertEquals(listOf(DelegationStatus.Running, DelegationStatus.Running), running.map { it.status })
        allow("K#id-Backend", first)
        allow("K#id-Tester", second)
        val seen = fixture.finished()
        assertEquals(listOf(DelegationStatus.Done, DelegationStatus.Done), seen.items.delegations().map { it.status })
        assertEquals("one", a.readText())
        assertEquals("two", b.readText())
    }

    @Test
    fun `the tasks of one member in a round run in order, the second told as a new task`() {
        val team = team("Backend")
        fixture.send(team, "delegate Backend: echo one | Backend: echo two")
        val seen = fixture.finished()
        assertEquals(listOf("one", "two"), seen.items.delegations().map { it.result })
        val member = fixture.idle("K#id-Backend") { it.answers().size == 2 }
        assertEquals(listOf("echo one", "echo two"), member.items.userTexts())
        assertEquals(listOf("one ", "two "), member.items.answers())
        val received = promptsIn(fixture.storeOf("Backend"))
        assertEquals(2, received.size)
        assertTrue(received[0].startsWith("You are Backend, a member of the team \"Crew\"."), received[0])
        assertTrue(received[0].endsWith("# User request\n\necho one"), received[0])
        val intro = "Lead gave you a new task. It is in the last section of this message."
        assertEquals(lines(intro, "", "# User request", "", "echo two"), received[1])
        val final =
            seen.items
                .answers()
                .lastOrNull()
                .orEmpty()
        assertTrue(final.indexOf("Task: echo one") < final.indexOf("Task: echo two"), final)
    }

    @Test
    fun `a second round answers the reports of the first, and a member remembers its earlier tasks`() {
        val team = team("Backend", "Tester")
        fixture.send(team, "delegate Backend: echo a ;; Tester: echo b ;; Backend: count")
        val seen = fixture.finished()
        val items = seen.items.delegations()
        assertEquals(listOf("Backend", "Tester", "Backend"), items.map { it.member })
        assertEquals(listOf("a", "b"), items.take(2).map { it.result })
        assertContains(items.getOrNull(2)?.result.orEmpty(), "2")
        val final =
            seen.items
                .answers()
                .lastOrNull()
                .orEmpty()
        assertFalse("## Tester" in final, final)
        assertEquals(1, promptsIn(fixture.storeOf("Tester")).size)
        assertEquals(2, promptsIn(fixture.storeOf("Backend")).size)
    }

    @Test
    fun `a member that crashes fails its item only, the manager is told and the other member succeeds`() {
        val team = team("Backend", "Tester")
        fixture.send(team, "delegate Backend: crash | Tester: echo fine")
        val seen = fixture.finished()
        val items = seen.items.delegations()
        assertEquals(
            listOf("Backend" to DelegationStatus.Failed, "Tester" to DelegationStatus.Done),
            statuses(seen.items),
        )
        val reason = items.firstOrNull()?.result.orEmpty()
        assertTrue(reason.isNotBlank() && reason != "The member failed.", reason)
        assertEquals("fine", items.getOrNull(1)?.result)
        val final =
            seen.items
                .answers()
                .lastOrNull()
                .orEmpty()
        assertContains(final, "## Backend (failed)")
        assertContains(final, reason)
        assertContains(final, "## Tester (done)")
    }

    @Test
    fun `a manager that sends a block that is not JSON is told so, as a round`() {
        fixture.send(team("Backend"), "delegate-malformed")
        val seen = fixture.finished()
        assertContains(
            seen.items
                .answers()
                .lastOrNull()
                .orEmpty(),
            "The `team` block is not valid JSON.",
        )
        assertEquals(emptyList(), seen.items.delegations())
        val received = promptsIn(fixture.storeOf("Lead"))
        assertContains(received.lastOrNull().orEmpty(), "Your last message could not be run as a delegation.")
    }

    @Test
    fun `a manager that names an unknown member is told which ones exist, and no task starts`() {
        fixture.send(team("Backend", "Tester"), "delegate Nobody: echo hi | Backend: echo hi")
        val seen = fixture.finished()
        val final =
            seen.items
                .answers()
                .lastOrNull()
                .orEmpty()
        assertContains(final, "There is no member named \"Nobody\". The members of the team are: Backend, Tester.")
        assertEquals(emptyList(), seen.items.delegations())
        assertEquals(emptyList(), promptsIn(fixture.storeOf("Backend")))
    }

    @Test
    fun `a manager that does not delegate ends the run with its answer`() {
        fixture.send(team("Backend"), "echo nothing to delegate")
        val seen = fixture.idle { items -> items.answers().isNotEmpty() }
        assertEquals(listOf("nothing to delegate "), seen.items.answers())
        assertEquals(emptyList(), seen.items.delegations())
        assertEquals(emptyList(), promptsIn(fixture.storeOf("Backend")))
    }

    @Test
    fun `a manager that fails ends the run, with the notice of the agent and none of the team`() {
        fixture.send(team("Backend"), "crash")
        val seen = fixture.idle { items -> items.notices().any { it.error } }
        assertEquals(1, seen.items.notices().count { it.error })
        assertFalse(seen.items.notices().any { it.text.startsWith("The team stopped") })
        assertEquals(AgentTurnState.Idle, seen.last.state)
    }

    @Test
    fun `a run stops after the rounds it may go through, with an error notice`() {
        fixture.close()
        fixture = TeamFixture(HarnessSettings(maxRounds = 2))
        members.clear()
        fixture.send(team("Backend"), "delegate Backend: echo 1 ;; Backend: echo 2 ;; Backend: echo 3")
        val seen = fixture.idle { items -> items.notices().any { it.error } }
        assertEquals(2, seen.items.delegations().size)
        assertEquals(
            listOf("The team stopped after 2 rounds without a final answer."),
            seen.items.notices().map { it.text },
        )
    }

    @Test
    fun `a prompt sent twice runs once`() {
        val team = team("Backend")
        fixture.send(team, "delegate Backend: echo hi", itemId = "same")
        fixture.send(team, "delegate Backend: echo hi", itemId = "same")
        val seen = fixture.finished()
        assertEquals(1, seen.items.userTexts().size)
        assertEquals(1, seen.items.delegations().size)
        assertEquals(2, promptsIn(fixture.storeOf("Lead")).size)
        fixture.send(team, "delegate Backend: echo hi", itemId = "same")
        assertEquals(
            1,
            fixture
                .until("K") { true }
                .items
                .userTexts()
                .size,
        )
    }

    @Test
    fun `a prompt sent during a run waits, then starts a second run, and the transcript is busy all along`() {
        val team = team("Backend")
        val file = fixture.project.resolve("f.txt")
        // The IDE follows the transcript from before the first prompt to the end of the second run.
        var followed: Followed? = null
        val follower = thread { followed = fixture.idle { items -> items.answers().any { it.startsWith("second") } } }
        fixture.send(team, "delegate Backend: write $file hi")
        val request = waitingPermission("K#id-Backend")
        fixture.send(team, "echo second")
        // It is not the manager's yet: it neither shows in the transcript nor reaches the agent.
        assertEquals(listOf("delegate Backend: write $file hi"), fixture.until("K") { true }.items.userTexts())
        allow("K#id-Backend", request)
        follower.join(TIMEOUT_MS)
        val seen = requireNotNull(followed)
        assertEquals(listOf("delegate Backend: write $file hi", "echo second"), seen.items.userTexts())
        assertTrue(seen.items.answers().any { it.startsWith(FakeManager.FINAL_PREFIX) })
        val received = promptsIn(fixture.storeOf("Lead"))
        assertEquals(3, received.size)
        assertTrue(received[2].endsWith("# User request\n\necho second"), received[2])
        assertFalse("# Your team" in received[2], "the preamble is sent once")
        assertBusyAllAlong(seen)
    }

    // Once the run showed busy, the transcript was idle only after the end: the answer to the second prompt.
    private fun assertBusyAllAlong(seen: Followed) {
        val started = seen.states.indexOfFirst { it.first != AgentTurnState.Idle }
        assertTrue(started >= 0, "the run never showed busy: ${seen.states}")
        val after = seen.states.drop(started)
        val idleTooSoon = after.filter { (state, done) -> state == AgentTurnState.Idle && !done }
        assertEquals(emptyList(), idleTooSoon, "idle between two turns of the run: ${seen.states}")
    }

    @Test
    fun `more prompts than the queue holds are refused as a plain conversation does, and may be sent again`() {
        fixture.close()
        fixture = TeamFixture(HarnessSettings(queuedPrompts = 1))
        members.clear()
        val team = team("Backend")
        val file = fixture.project.resolve("g.txt")
        fixture.send(team, "delegate Backend: write $file hi")
        val request = waitingPermission("K#id-Backend")
        fixture.send(team, "echo first", itemId = "q1")
        val refused = assertFailsWith<IllegalStateException> { fixture.send(team, "echo second", itemId = "q2") }
        assertEquals(AGENT_BUSY_MESSAGE, refused.message)
        allow("K#id-Backend", request)
        fixture.idle { items -> items.answers().any { it.startsWith("first") } }
        fixture.send(team, "echo second", itemId = "q2")
        val seen = fixture.idle { items -> items.answers().any { it.startsWith("second") } }
        assertEquals(listOf("delegate Backend: write $file hi", "echo first", "echo second"), seen.items.userTexts())
    }

    @Test
    fun `stop during the manager's turn writes one notice and nothing else`() {
        val team = team("Backend")
        fixture.send(team, "slow")
        fixture.until("K") { items -> items.answers().isNotEmpty() }
        fixture.harness.cancel("K")
        val seen = fixture.idle { items -> items.notices().isNotEmpty() }
        assertEquals(listOf("Stopped."), seen.items.notices().map { it.text })
        assertEquals(emptyList(), seen.items.delegations())
    }

    @Test
    fun `stop during the members' turns cancels them, marks their items and tells the manager nothing`() {
        val team = team("Backend", "Tester")
        fixture.send(team, "delegate Backend: slow | Tester: slow")
        fixture.until("K#id-Backend") { items -> items.answers().isNotEmpty() }
        fixture.until("K#id-Tester") { items -> items.answers().isNotEmpty() }
        fixture.harness.cancel("K")
        assertStoppedEverywhere()
    }

    @Test
    fun `stop sent to the service rather than the harness stops the members too`() {
        val team = team("Backend", "Tester")
        fixture.send(team, "delegate Backend: slow | Tester: slow")
        fixture.until("K#id-Backend") { items -> items.answers().isNotEmpty() }
        fixture.until("K#id-Tester") { items -> items.answers().isNotEmpty() }
        fixture.service.cancel("K")
        assertStoppedEverywhere()
    }

    // The run is over: items cancelled, one notice of the manager, its members stopped, no more prompt to the manager.
    private fun assertStoppedEverywhere() {
        val seen = fixture.idle { items -> items.delegations().all { it.status != DelegationStatus.Running } }
        val items = seen.items
        assertEquals(
            listOf(DelegationStatus.Cancelled, DelegationStatus.Cancelled),
            items.delegations().map { it.status },
        )
        val notices = fixture.idle { it.notices().isNotEmpty() }.items.notices()
        assertEquals(listOf("Stopped."), notices.map { it.text })
        assertEquals(1, items.answers().size)
        for (name in listOf("Backend", "Tester")) {
            val member = fixture.idle("K#id-$name") { it.notices().isNotEmpty() }
            assertEquals(listOf("Stopped."), member.items.notices().map { it.text })
        }
        assertEquals(1, promptsIn(fixture.storeOf("Lead")).size)
    }

    @Test
    fun `closing during a run closes the members and leaves nothing behind`() {
        val team = team("Backend", "Tester")
        fixture.send(team, "delegate Backend: slow | Tester: slow")
        fixture.until("K#id-Backend") { items -> items.answers().isNotEmpty() }
        fixture.until("K#id-Tester") { items -> items.answers().isNotEmpty() }
        assertTrue(fixture.harness.isTeam("K"))
        runBlocking { fixture.harness.close("K") }
        assertFalse(fixture.harness.isTeam("K"))
        assertEquals(0, fixture.harness.tracked())
        // The transcripts are asked after the hold delay: a conversation the run made again would show.
        assertFalse(fixture.service.knows("K"))
        assertFalse(fixture.service.knows("K#id-Backend"))
        assertFalse(fixture.service.knows("K#id-Tester"))
        assertEquals(0, fixture.harness.tracked())
    }

    @Test
    fun `a conversation closed when idle releases what the harness kept, and may start again`() {
        val team = team("Backend")
        fixture.send(team, "delegate Backend: echo hi")
        fixture.finished()
        runBlocking { fixture.harness.close("K") }
        assertEquals(0, fixture.harness.tracked())
        assertFalse(fixture.service.knows("K#id-Backend"))
        fixture.send(team, "echo again")
        val seen = fixture.idle { items -> items.answers().isNotEmpty() }
        assertEquals(listOf("echo again"), seen.items.userTexts())
    }

    @Test
    fun `the settings of an agent are set before its first prompt, and one it refuses is told in the team`() {
        val manager = fixture.agent("Lead", settings = mapOf("model" to "fake-smart"))
        val backend = fixture.agent("Backend", settings = mapOf("model" to "fake-smart"))
        val tester = fixture.agent("Tester", settings = mapOf("model" to "fake-nope"))
        members["Lead"] = manager
        members["Backend"] = backend
        members["Tester"] = tester
        fixture.send(fixture.team(manager, backend, tester), "delegate Backend: model | Tester: model")
        val seen = fixture.finished()
        assertEquals(listOf("Model: fake-smart.", "Model: fake-fast."), seen.items.delegations().map { it.result })
        assertEquals(listOf("fake-smart"), settingsIn(fixture.storeOf("Lead"), "model"))
        val refused =
            seen.items
                .notices()
                .filter { it.error }
                .map { it.text }
        assertEquals(1, refused.size, refused.toString())
        assertContains(refused.single(), "Tester: The agent refused fake-nope for model")
    }

    @Test
    fun `the permission mode chosen in the conversation reaches the members, those not started yet too`() {
        val team = team("Backend", "Tester")
        runBlocking { fixture.harness.setOption("K", lead.agent, "mode", "plan") }
        fixture.send(team, "delegate Backend: mode")
        assertEquals(
            "Mode: plan.",
            fixture
                .finished()
                .items
                .delegations()
                .lastOrNull()
                ?.result,
        )
        runBlocking { fixture.harness.setOption("K", lead.agent, "mode", "accept-edits") }
        fixture.send(team, "delegate Backend: mode | Tester: mode")
        val seen = fixture.idle { items -> items.delegations().size == 3 && items.answers().size >= 4 }
        val second =
            seen.items
                .delegations()
                .drop(1)
                .map { it.result }
        assertEquals(listOf("Mode: accept-edits.", "Mode: accept-edits."), second)
        assertEquals(listOf("accept-edits"), settingsIn(fixture.storeOf("Tester"), "mode"))
    }

    @Test
    fun `skills are listed one line each in the preamble, and a skill that is gone is reported and skipped`() {
        val folder = fixture.project.resolve(".claude/skills/review")
        Files.createDirectories(folder)
        val skill = lines("---", "name: review", "description: Reviews a change", "---", "Steps.")
        Files.writeString(folder.resolve("SKILL.md"), skill)
        val gone = fixture.project.resolve("skills/gone").toString()
        val manager = fixture.agent("Lead", skills = listOf(folder.toString()), systemPrompt = "Be brief.")
        val backend = fixture.agent("Backend", skills = listOf(gone, folder.toString()))
        members["Lead"] = manager
        members["Backend"] = backend
        fixture.send(fixture.team(manager, backend), "delegate Backend: echo hi")
        val seen = fixture.finished()
        val skillLine = "- review: Reviews a change ($folder/SKILL.md)"
        val managerPrompt = promptsIn(fixture.storeOf("Lead")).firstOrNull().orEmpty()
        assertContains(managerPrompt, "# Your skills\nRead the SKILL.md of a skill before you use it.\n$skillLine")
        assertContains(managerPrompt, "# Your instructions\nBe brief.")
        val memberPrompt = promptsIn(fixture.storeOf("Backend")).firstOrNull().orEmpty()
        assertContains(memberPrompt, skillLine)
        assertFalse("gone" in memberPrompt, memberPrompt)
        val skipped = seen.items.notices().map { it.text }
        assertEquals(listOf("Backend: the skill $gone does not exist any more, skipped."), skipped)
    }

    @Test
    fun `a member whose turn failed gets its preamble again with its next task`() {
        fixture.send(team("Backend"), "delegate Backend: crash ;; Backend: echo again")
        val seen = fixture.finished()
        assertEquals(listOf(DelegationStatus.Failed, DelegationStatus.Done), seen.items.delegations().map { it.status })
        val retried = promptsIn(fixture.storeOf("Backend")).filter { it.endsWith("echo again") }
        assertEquals(1, retried.size)
        assertTrue(retried.firstOrNull().orEmpty().startsWith("You are Backend"), retried.toString())
    }

    @Test
    fun `a manager whose first turn failed gets its preamble with the next prompt`() {
        val team = team("Backend")
        fixture.send(team, "crash")
        fixture.idle { items -> items.notices().any { it.error } }
        fixture.send(team, "echo hello")
        val seen = fixture.idle { items -> items.answers().any { it.startsWith("hello") } }
        assertEquals(listOf("crash", "echo hello"), seen.items.filterIsInstance<AgentItem.UserText>().map { it.text })
        val retried = promptsIn(fixture.storeOf("Lead")).filter { it.endsWith("echo hello") }
        assertTrue(retried.firstOrNull().orEmpty().startsWith("You are Lead, the manager"), retried.toString())
    }

    @Test
    fun `a stop that lands between the manager's turn and the round cancels the round before any member starts`() {
        fixture.close()
        val stopsBeforeDelegating =
            object : DelegationProtocol by TextDelegationProtocol() {
                override fun parse(answer: String, team: Team): ManagerReply {
                    fixture.harness.cancel("K")
                    return TextDelegationProtocol().parse(answer, team)
                }
            }
        fixture = TeamFixture(protocol = stopsBeforeDelegating)
        members.clear()
        fixture.send(team("Backend"), "delegate Backend: echo never")
        val seen = fixture.idle { items -> items.notices().isNotEmpty() }
        assertEquals(listOf("Stopped."), seen.items.notices().map { it.text })
        assertEquals(listOf(DelegationStatus.Cancelled), seen.items.delegations().map { it.status })
        assertEquals(emptyList(), promptsIn(fixture.storeOf("Backend")))
        assertEquals(1, promptsIn(fixture.storeOf("Lead")).size)
    }

    @Test
    fun `agents that share one process each get their own session, and a refused setting is told once`() {
        val manager = fixture.agent("Lead", shared = true)
        val backend = fixture.agent("Backend", settings = mapOf("model" to "fake-nope"), shared = true)
        val tester = fixture.agent("Tester", shared = true)
        members["Lead"] = manager
        members["Backend"] = backend
        members["Tester"] = tester
        fixture.send(fixture.team(manager, backend, tester), "delegate Backend: model | Tester: model")
        val seen = fixture.finished()
        assertEquals(listOf("Model: fake-fast.", "Model: fake-fast."), seen.items.delegations().map { it.result })
        val refused =
            seen.items
                .notices()
                .filter { it.error }
                .map { it.text }
        assertEquals(1, refused.size, refused.toString())
        assertContains(refused.firstOrNull().orEmpty(), "Backend: The agent refused fake-nope for model")
    }

    @Test
    fun `the manager's first prompt carries its preamble, ends with the request, and the later ones do not`() {
        val team = team("Backend", "Tester")
        fixture.send(team, "delegate Backend: echo hi")
        fixture.finished()
        val received = promptsIn(fixture.storeOf("Lead"))
        val first = received.firstOrNull().orEmpty()
        assertTrue(first.startsWith("You are Lead, the manager of the team \"Crew\". does Lead things"), first)
        assertContains(first, "# Your team\n- Backend: does Backend things\n- Tester: does Tester things")
        assertContains(first, "# How to delegate\nTo hand tasks to your members")
        val example = """{"delegate": [{"member": "Backend", "task": "..."}]}"""
        assertContains(first, lines("```team", example, "```"))
        assertTrue(first.endsWith(lines("", "# User request", "", "delegate Backend: echo hi")), first)
        val relay = received.getOrNull(1).orEmpty()
        assertTrue(relay.startsWith("Your members finished the tasks of this round."), relay)
        val reports = lines("# User request", "", "## Backend (done)", "Task: echo hi", "", "hi")
        assertTrue(relay.endsWith(reports), relay)
    }
}

private fun lines(vararg parts: String): String = parts.joinToString("\n")

private fun List<AgentItem>.userTexts(): List<String> = filterIsInstance<AgentItem.UserText>().map { it.text }
