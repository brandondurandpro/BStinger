package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.kotlineditor.plugin.core.API_VERSION
import org.example.kotlineditor.plugin.host.PluginProblem
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.PluginMethod
import org.example.kotlineditor.testkit.PluginFixture
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val BUTTON = "plugin.todo.button"
private val SCAN = PluginMethod("todo", "scan", mutating = false)

private val CLIENT_HALF =
    """
    c.toolWindow(new org.example.kotlineditor.plugin.ToolWindowContribution(
        new org.example.kotlineditor.state.ToolWindowDefaults(
            new org.example.kotlineditor.state.ToolWindow("plugin.todo"),
            org.example.kotlineditor.state.Dock.Bottom,
            false),
        "$BUTTON",
        org.example.kotlineditor.plugin.StripGroup.Bottom,
        false,
        host -> kotlin.Unit.INSTANCE,
        (composer, changed) -> kotlin.Unit.INSTANCE,
        (host, drag, modifier, composer, changed) -> kotlin.Unit.INSTANCE));
    """.trimIndent()

private fun serverHalf(answer: String) =
    """
    c.handle(
        new org.example.kotlineditor.rpc.PluginMethod("todo", "scan", false),
        (request, continuation) -> kotlinx.serialization.json.JsonElementKt.JsonPrimitive("$answer"));
    """.trimIndent()

private fun JsonElement.ids(): List<String> {
    val entries = jsonArray
    return entries.map { entry ->
        val fields = entry.jsonObject
        fields.getValue("id").jsonPrimitive.content
    }
}

@OptIn(ExperimentalTestApi::class)
class ServerPluginE2eTest {
    private val project = SampleProject.standard()
    private val plugins: File = Files.createTempDirectory("ide-plugins").toFile()

    @AfterTest
    fun cleanup() {
        project.delete()
        plugins.deleteRecursively()
    }

    private fun E2eApp.scan(): String {
        val answer = runBlocking { client.call(SCAN) }
        return answer.jsonPrimitive.content
    }

    private fun E2eApp.listedOnServer(): List<String> {
        val listed = runBlocking { client.call(Method.PluginList) }
        return listed.ids()
    }

    @Test
    fun `the server half of a plugin is installed on the server and answers its methods`() =
        runComposeUiTest {
            PluginFixture.createWithServer(plugins, "todo", API_VERSION, CLIENT_HALF, serverHalf("todo-list"))
            val app = launchWithProject(project, pluginsDirectory = plugins)

            awaitCondition { app.listedOnServer() == listOf("todo") }
            assertEquals("todo-list", app.scan())
            awaitTag(BUTTON)
        }

    @Test
    fun `a server restarted with its installed halves still answers`() =
        runComposeUiTest {
            PluginFixture.createWithServer(plugins, "todo", API_VERSION, CLIENT_HALF, serverHalf("todo-list"))
            val app = launchWithProject(project, pluginsDirectory = plugins)
            awaitCondition { app.listedOnServer() == listOf("todo") }

            app.process.restart()

            awaitCondition { runCatching { app.scan() }.getOrNull() == "todo-list" }
        }

    @Test
    fun `a server that lost its halves gets them again when the client reconnects`() =
        runComposeUiTest {
            PluginFixture.createWithServer(plugins, "todo", API_VERSION, CLIENT_HALF, serverHalf("todo-list"))
            val app = launchWithProject(project, pluginsDirectory = plugins)
            awaitCondition { app.listedOnServer() == listOf("todo") }

            app.process.stop()
            app.process.serverPlugins.deleteRecursively()
            app.process.start()

            awaitCondition { runCatching { app.scan() }.getOrNull() == "todo-list" }
        }

    @Test
    fun `a plugin whose server half fails to load is off on both sides`() =
        runComposeUiTest {
            val broken = "throw new IllegalStateException(\"no disk\");"
            PluginFixture.createWithServer(plugins, "todo", API_VERSION, CLIENT_HALF, broken)
            val app = launchWithProject(project, pluginsDirectory = plugins)
            val installed = checkNotNull(app.installedPlugins)

            awaitCondition { installed.offBecause["todo"] != null }
            assertIs<PluginProblem.ServerUnavailable>(installed.offBecause["todo"])
            assertEquals(emptyList(), installed.loaded)
            awaitNoTag(BUTTON)
            assertEquals(emptyList(), app.listedOnServer())
        }

    @Test
    fun `a client plugin without a server half leaves the server untouched`() =
        runComposeUiTest {
            PluginFixture.create(plugins, "todo", API_VERSION, CLIENT_HALF)
            val app = launchWithProject(project, pluginsDirectory = plugins)

            awaitTag(BUTTON)
            assertTrue(app.listedOnServer().isEmpty())
            val folder = app.process.serverPlugins
            val installedFiles = folder.list().orEmpty()
            assertTrue(installedFiles.isEmpty())
        }
}
