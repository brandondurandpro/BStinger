package org.example.kotlineditor.acp

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolKind
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.testkit.acp.FakeAgentCommand
import org.example.kotlineditor.testkit.acp.FakeProgram
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val GONE_TIMEOUT_MS = 20_000L
private const val GONE_POLL_MS = 50L

/** The commands an agent runs on the server (`terminal/create` and the rest), shown in its tool calls. */
class AgentTerminalTest {
    private val service = AgentService(settings = AgentSettings(holdMs = 2_000, cancelGraceMs = 5_000))
    private val agent = AgentCommand(FakeAgentCommand.command())
    private val directory = Files.createTempDirectory("agent-terminal")
    private var prompts = 0

    @AfterTest
    fun tearDown() {
        service.close()
        directory.toFile().deleteRecursively()
    }

    private fun send(text: String) = service.prompt("c1", agent, directory.toString(), "u${++prompts}", text)

    private fun List<AgentItem>.call(): AgentItem.ToolCall? = filterIsInstance<AgentItem.ToolCall>().lastOrNull()

    // The programs of the fake agent still running.
    private fun running(): Long {
        val name = FakeProgram::class.java.simpleName
        return ProcessHandle
            .allProcesses()
            .filter { process -> commandOf(process).contains(name) }
            .count()
    }

    private fun commandOf(process: ProcessHandle): String = process.info().commandLine().orElse("")

    // The lines a command printed, without the notice a JVM prints when JAVA_TOOL_OPTIONS is set (as on the CI).
    private fun String.printed(): List<String> = lines().filterNot { it.startsWith("Picked up JAVA_TOOL_OPTIONS") }

    // The output of the command of the latest tool call.
    private fun List<AgentItem>.output(): String = call()?.terminal?.text.orEmpty()

    @Test
    fun `a command shows its output and exit code in its tool call`() {
        send("run 3 0")
        val items = service.idle("c1") { it.answers().isNotEmpty() }.items
        val call = items.call()
        assertEquals(AgentToolKind.Execute, call?.kind)
        assertEquals(AgentToolStatus.Completed, call?.status)
        assertEquals(listOf("line 1", "line 2", "line 3", ""), call?.terminal?.text?.printed())
        assertEquals(0, call?.terminal?.exitCode)
        assertEquals(listOf("Exit code 0, 3 lines."), items.answers())
    }

    @Test
    fun `a failing command shows its error output and its exit code`() {
        send("run 2 5")
        val items = service.idle("c1") { it.answers().isNotEmpty() }.items
        val call = items.call()
        assertEquals(AgentToolStatus.Failed, call?.status)
        assertEquals(5, call?.terminal?.exitCode)
        assertContains(call?.terminal?.text.orEmpty(), "failed with 5")
    }

    @Test
    fun `cancelling the prompt kills its command`() {
        send("run forever")
        service.follow("c1") { _, items -> "tick 3" in items.output() }
        service.cancel("c1")
        val items = service.idle("c1") { it.answers().isNotEmpty() }.items
        assertEquals(listOf("Killed."), items.answers())
        assertTrue(items.call()?.terminal?.exitCode != null)
    }

    @Test
    fun `the output is kept up to the limit the agent sets, the oldest dropped`() {
        send("run big 20000")
        val items = service.idle("c1") { it.answers().isNotEmpty() }.items
        assertContains(items.answers().joinToString(), "truncated")
        val shown = items.call()?.terminal
        assertTrue(shown?.truncated == true)
        assertContains(shown?.text.orEmpty(), "line 20000")
    }

    @Test
    fun `closing the conversation kills the commands of its session`() {
        send("run forever")
        service.follow("c1") { _, items -> "tick 2" in items.output() }
        runBlocking { service.close("c1") }
        val deadline = System.currentTimeMillis() + GONE_TIMEOUT_MS
        while (running() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(GONE_POLL_MS)
        assertEquals(0, running())
    }
}
