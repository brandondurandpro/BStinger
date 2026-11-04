package org.example.kotlineditor.acp

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.lsp.provision.RuntimeDetector
import org.example.kotlineditor.lsp.provision.ToolDownloader
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.testkit.acp.FakeRegistry
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The agents of the ACP registry, installed and started by the server (a registry on loopback). */
class AgentRegistryTest {
    private val platform = checkNotNull(registryPlatform()) { "Unknown platform" }
    private val registry = FakeRegistry(platform)
    private val tools = Files.createTempDirectory("agent-tools")
    private val directory = Files.createTempDirectory("agent-registry").toString()
    private val services = mutableListOf<AgentService>()

    @AfterTest
    fun tearDown() {
        services.forEach(AgentService::close)
        registry.close()
        tools.toFile().deleteRecursively()
    }

    private fun server(): AgentService {
        val settings = AgentSettings(holdMs = 2_000, registryUrl = registry.url, toolsDirectory = tools)
        return AgentService(settings = settings).also(services::add)
    }

    @Test
    fun `the registry says which agents this server can run`() {
        val entries = server().processes.registry().associateBy { it.id }
        assertNull(entries["fake-agent"]?.unavailable)
        assertContains(entries["unsigned-agent"]?.unavailable.orEmpty(), "no checksum")
        assertNotNull(entries["elsewhere-agent"]?.unavailable)
        assertEquals("Fake Agent", entries["fake-agent"]?.name)
    }

    @Test
    fun `an agent of the registry is downloaded once, verified, and runs`() {
        val agent = AgentCommand(emptyList(), registry = "fake-agent")
        val first = server()
        first.prompt("c1", agent, directory, "u1", "echo installed")
        assertEquals(listOf("installed "), first.idle("c1") { it.answers().isNotEmpty() }.items.answers())
        first.close()
        val again = server()
        again.prompt("c1", agent, directory, "u1", "echo again")
        assertEquals(listOf("again "), again.idle("c1") { it.answers().isNotEmpty() }.items.answers())
        assertEquals(1, registry.downloads.get())
    }

    @Test
    fun `an agent without a checksum is never run, and the conversation says why`() {
        val server = server()
        server.prompt("c1", AgentCommand(emptyList(), registry = "unsigned-agent"), directory, "u1", "echo hi")
        val items = server.idle("c1") { list -> list.any { it is AgentItem.Notice } }.items
        assertContains(items.filterIsInstance<AgentItem.Notice>().joinToString { it.text }, "no checksum")
        assertEquals(0, registry.downloads.get())
    }

    @Test
    fun `the history of an agent of the registry is listed`() {
        val server = server()
        val agent = AgentCommand(emptyList(), registry = "fake-agent")
        server.prompt("c1", agent, directory, "u1", "echo kept")
        server.idle("c1") { it.answers().isNotEmpty() }
        val sessions = runBlocking { server.processes.sessions(agent, listOf(directory)) }
        assertEquals(1, sessions.sessions.size)
    }

    @Test
    fun `an npx agent runs on the Node js of the machine, found first on the PATH`() {
        val bin = Files.createTempDirectory("node-bin")
        val node = bin.resolve("node").toFile().apply { writeText("") }
        node.setExecutable(true)
        val runtimes =
            RuntimeDetector(
                pathEnvironment = bin.toString(),
                isWindows = false,
                extraDirectories = { emptyList() },
                probe = { _, _ -> "v22.1.0" },
            )
        val installer = AgentInstaller(ToolDownloader(tools), runtimes, "linux-x86_64", windows = false)
        val npx = NPX_REGISTRY.let(::parseRegistry).first()
        val command = installer.command(npx)
        val expected = listOf(bin.resolve("npx").toString(), "-y", "@example/npx-agent@2.0.0", "--acp")
        assertEquals(expected, command.command)
        assertEquals("acp", command.environment["MODE"])
        assertEquals(bin.toString(), command.environment["PATH"]?.substringBefore(File.pathSeparator))
        bin.toFile().deleteRecursively()
    }

    @Test
    fun `an npx agent without Node js says what it needs`() {
        val runtimes = RuntimeDetector(pathEnvironment = "", extraDirectories = { emptyList() })
        val installer = AgentInstaller(ToolDownloader(tools), runtimes, "linux-x86_64", windows = false)
        assertContains(installer.unavailable(parseRegistry(NPX_REGISTRY).first()).orEmpty(), "Node.js 18")
    }

    @Test
    fun `the platforms are named as the registry names them`() {
        assertEquals("linux-x86_64", registryPlatform("Linux", "amd64"))
        assertEquals("darwin-aarch64", registryPlatform("Mac OS X", "aarch64"))
        assertEquals("windows-x86_64", registryPlatform("Windows 11", "amd64"))
        assertNull(registryPlatform("SunOS", "sparc"))
    }

    private companion object {
        const val NPX_REGISTRY =
            """{"version":"1.0.0","agents":[{"id":"npx-agent","name":"Npx Agent","version":"2.0.0",
            "description":"An npm package","distribution":{"npx":{"package":"@example/npx-agent@2.0.0",
            "args":["--acp"],"env":{"MODE":"acp"}}}}]}"""
    }
}
