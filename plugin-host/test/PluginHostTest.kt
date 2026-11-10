package org.example.kotlineditor.plugin.host

import org.example.kotlineditor.plugin.IdePlugin
import org.example.kotlineditor.testkit.PluginFixture
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginHostTest {
    private val directory = Files.createTempDirectory("plugins").toFile()
    private val installed = mutableListOf<IdePlugin>()
    private val uninstalled = mutableListOf<String>()
    private val host =
        PluginHost { plugin ->
            installed += plugin
            AutoCloseable { uninstalled += plugin.id }
        }

    private fun fixture(id: String, apiVersion: Int = API_VERSION, body: String = "", extraManifest: String = "") =
        PluginFixture.create(directory, id, apiVersion, body, extraManifest)

    @AfterTest
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun `a manifest is read with its lists and comments`() {
        val manifest =
            PluginManifest.parse(
                """
                # a plugin
                id: todo
                name: "Todo explorer"
                version: 1.2
                apiVersion: 1
                client: a.B
                permissions:
                  - process   # scans
                  - fs-write
                """.trimIndent(),
            )
        assertEquals("Todo explorer", manifest.name)
        assertEquals(listOf("process", "fs-write"), manifest.permissions)
        assertNull(manifest.server)
    }

    @Test
    fun `a manifest without an id is refused`() {
        assertFailsWith<IllegalArgumentException> { PluginManifest.parse("version: 1\napiVersion: 1") }
    }

    @Test
    fun `a plugin loads from its folder and registers what it contributes`() {
        val folder = fixture("todo")
        val result = host.load(folder)

        assertNotNull(result.loaded)
        assertEquals(listOf("todo"), host.loadedIds)
        assertEquals(listOf("todo"), installed.map { it.id })
        assertTrue(!File(folder, ".loading").exists(), "the start marker is removed once the plugin runs")
    }

    @Test
    fun `unloading removes its contributions and drops its class loader`() {
        host.load(fixture("todo"))

        assertEquals(UnloadOutcome.Clean, host.unload("todo"))
        assertEquals(listOf("todo"), uninstalled)
        assertEquals(emptyList(), host.loadedIds)
    }

    @Test
    fun `a plugin kept alive by a thread is reported as needing a restart`() {
        val body =
            "Thread t = new Thread(() -> { try { Thread.sleep(60000); } catch (Exception e) {} });" +
                " t.setDaemon(true); t.start();"
        host.load(fixture("leaky", body = body))

        assertEquals(UnloadOutcome.RestartRequired, host.unload("leaky"))
    }

    @Test
    fun `a plugin built for another API does not load`() {
        val result = host.load(fixture("old", apiVersion = API_VERSION + 1))
        assertIs<PluginProblem.ApiMismatch>(result.problem)
        assertEquals(emptyList(), installed)
    }

    @Test
    fun `a plugin whose start did not finish stays off at the next start, until it is enabled`() {
        val folder = fixture("crashy")
        File(folder, ".loading").writeText("1.0")

        assertIs<PluginProblem.Crashed>(host.load(folder).problem)
        host.enable(folder)
        assertNotNull(host.load(folder).loaded)
    }

    @Test
    fun `a plugin that throws while registering is reported and leaves the marker`() {
        val folder = fixture("bad", body = "throw new IllegalStateException(\"boom\");")

        val problem = host.load(folder).problem
        assertIs<PluginProblem.Failed>(problem)
        assertEquals("boom", problem.reason)
        assertTrue(File(folder, ".loading").exists())
    }

    @Test
    fun `a disabled plugin is not started`() {
        val folder = fixture("todo")
        host.disable(folder)
        assertIs<PluginProblem.Disabled>(host.load(folder).problem)
    }

    @Test
    fun `a plugin cannot see the internals of the IDE`() {
        val body =
            "try { Class.forName(\"org.example.kotlineditor.server.EditorServer\"); }" +
                " catch (ClassNotFoundException e) { return; } throw new IllegalStateException(\"visible\");"
        assertNotNull(host.load(fixture("peek", body = body)).loaded)
    }

    @Test
    fun `a plugin needs its dependencies loaded first`() {
        val folder = fixture("child", extraManifest = "dependencies:\n  - parent\n")
        val problem = host.load(folder).problem
        assertIs<PluginProblem.MissingDependency>(problem)
        assertEquals("parent", problem.id)
    }
}
