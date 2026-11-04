package org.example.kotlineditor.acp

import org.example.kotlineditor.model.AgentAttachment
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.testkit.acp.FakeAgentCommand
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** What the user joins to a prompt: files, lines, images, sent as the agent takes them. */
class AgentContextTest {
    private val settings = AgentSettings(holdMs = 2_000, cancelGraceMs = 1_000)
    private val store = Files.createTempDirectory("agent-store")
    private val directory = Files.createTempDirectory("agent-context")
    private val services = mutableListOf<AgentService>()
    private var prompts = 0

    init {
        Files.createDirectories(directory.resolve("src"))
        directory.resolve("src/Main.kt").writeText(listOf("fun main() {", "    println(1)", "}", "").joinToString("\n"))
        directory.resolve("logo.png").writeBytes(PNG)
        directory.resolve("data.bin").writeBytes(byteArrayOf(0, 1, 2, -1, -2))
    }

    @AfterTest
    fun tearDown() {
        services.forEach(AgentService::close)
        store.toFile().deleteRecursively()
        directory.toFile().deleteRecursively()
    }

    private fun server(): AgentService = AgentService(settings = settings).also(services::add)

    private fun agent(vararg options: String) = AgentCommand(FakeAgentCommand.command("--store", "$store", *options))

    // The answer of the agent to `context` with [attachments], and the notices of the prompt.
    private fun contextOf(vararg attachments: AgentAttachment, agent: AgentCommand = agent()): Pair<String, String> {
        val server = server()
        server.prompt("c1", agent, directory.toString(), "u${++prompts}", "context", null, attachments.toList())
        val items = server.idle("c1") { it.answers().isNotEmpty() }.items
        val notices = items.filterIsInstance<AgentItem.Notice>().joinToString { it.text }
        return items.answers().joinToString() to notices
    }

    @Test
    fun `a file goes with its text, lines with theirs, and the prompt shows what was joined`() {
        val lines = AgentAttachment.Lines("src/Main.kt", 2, 3, "    println(1)\n}")
        val server = server()
        val attachments = listOf(AgentAttachment.File("src/Main.kt"), lines)
        server.prompt("c1", agent(), directory.toString(), "u1", "context", null, attachments)
        val items = server.idle("c1") { it.answers().isNotEmpty() }.items
        assertEquals(
            listOf("Context: file Main.kt (3 lines); lines Main.kt#L2-L3 (2 lines)."),
            items.answers(),
        )
        val prompt = items.filterIsInstance<AgentItem.UserText>().firstOrNull()
        assertEquals(listOf("Main.kt", "Main.kt:2-3"), prompt?.context)
    }

    @Test
    fun `an agent without embedded context gets links, and the lines quoted in the text`() {
        val lines = AgentAttachment.Lines("src/Main.kt", 2, 2, "    println(1)")
        val (answer, _) = contextOf(AgentAttachment.File("src/Main.kt"), lines, agent = agent("--no-embedded"))
        assertEquals("Context: link Main.kt; text (4 lines).", answer)
    }

    @Test
    fun `images go as images, binary files as links`() {
        val pasted = AgentAttachment.Image("pasted.png", "image/png", Base64.getEncoder().encodeToString(PNG))
        val (answer, notices) =
            contextOf(AgentAttachment.File("logo.png"), pasted, AgentAttachment.File("data.bin"))
        val image = "image image/png (${PNG.size} bytes)"
        assertEquals("Context: $image; $image; link data.bin.", answer)
        assertEquals("", notices)
    }

    @Test
    fun `an agent without images gets image files as links, and a pasted one is left out with a notice`() {
        val pasted = AgentAttachment.Image("pasted.png", "image/png", Base64.getEncoder().encodeToString(PNG))
        val (answer, notices) = contextOf(AgentAttachment.File("logo.png"), pasted, agent = agent("--no-images"))
        assertEquals("Context: link logo.png.", answer)
        assertContains(notices, "pasted.png was not sent: the agent takes no images")
    }

    @Test
    fun `a file deleted or outside the directory is left out with a notice, the prompt goes`() {
        val (answer, notices) =
            contextOf(AgentAttachment.File("gone.kt"), AgentAttachment.File("../secret.txt"))
        assertEquals("Context: none.", answer)
        assertContains(notices, "gone.kt was not sent: the file no longer exists")
        assertContains(notices, "secret.txt was not sent: it is outside the directory of the agent")
    }

    @Test
    fun `a session replayed shows what was joined to its prompts`() {
        val first = server()
        val attachments = listOf(AgentAttachment.File("src/Main.kt"), AgentAttachment.Lines("src/Main.kt", 1, 1, "x"))
        first.prompt("c1", agent(), directory.toString(), "u1", "context", null, attachments)
        val session = first.idle("c1") { it.answers().isNotEmpty() }.last.session
        first.close()
        val second = server()
        second.open("c2", agent(), directory.toString(), checkNotNull(session))
        val items = second.idle("c2") { it.answers().isNotEmpty() }.items
        val prompt = items.filterIsInstance<AgentItem.UserText>().firstOrNull()
        assertEquals("context", prompt?.text)
        assertEquals(listOf("Main.kt", "Main.kt:1"), prompt?.context)
    }

    private companion object {
        // A PNG of one pixel.
        val PNG: ByteArray =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
            )
    }
}
