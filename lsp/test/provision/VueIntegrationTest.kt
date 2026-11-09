package org.example.kotlineditor.lsp.provision

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.lsp.LanguageServerService
import org.example.kotlineditor.lsp.LanguageServerSettings
import org.example.kotlineditor.lsp.supervisor.SupervisorTimings
import org.example.kotlineditor.model.CompletionResult
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val ANSWER_MS = 120_000L
private const val SESSION = "s1"
private const val TSCONFIG =
    """{"compilerOptions": {"strict": true, "module": "esnext", "moduleResolution": "bundler"}}"""

private val TYPED_VUE =
    listOf(
        "<script setup lang=\"ts\">",
        "const greeting: string = 'hello'",
        "</script>",
        "",
        "<template>",
        "  <p>{{ greeting.nope }}</p>",
        "  <",
        "</template>",
        "",
    ).joinToString("\n")

private val APP_VUE =
    listOf(
        "<script setup lang=\"ts\">",
        "const greeting: string = 'hello'",
        "</script>",
        "",
        "<template>",
        "  <p>{{ greeting }}</p>",
        "  <",
        "</template>",
        "",
    ).joinToString("\n")

/**
 * The real Vue server beside the TypeScript one, both downloaded at their pins and run by the real supervisor: the
 * Vue server asks the TypeScript one its questions through the client, and completes the elements of a template.
 * Skipped when no Node.js recent enough is installed.
 */
class VueIntegrationTest {
    private val project = createTempDirectory("vue-it")
    private val data = createTempDirectory("vue-it-data")
    private val cache = Paths.get(System.getProperty("user.home"), ".cache", "kotlin-editor", "test-tools")

    private val service by lazy {
        LanguageServerService(
            LanguageServerSettings(
                dataDirectory = data,
                toolsDirectory = cache,
                timings = SupervisorTimings(initializeTimeoutMs = 90_000, firstBackoffMs = 200),
            ),
        )
    }

    @AfterTest
    fun cleanup() {
        service.close()
        project.toFile().deleteRecursively()
        data.toFile().deleteRecursively()
    }

    private fun nodeAvailable(): Boolean {
        val node = WebServers.vueLanguageServer.requirements.firstOrNull()
        val detected = node?.let { RuntimeDetector().detect(it.runtime, it.minimumVersion, it.extensions) }
        if (detected !is RuntimeStatus.Found) println("SKIPPED: no Node.js for the Vue server ($detected)")
        return detected is RuntimeStatus.Found
    }

    @Test
    fun `the components of a template are proposed by the Vue server, with the names from the TypeScript one`() {
        if (!nodeAvailable()) return
        Files.writeString(project.resolve("tsconfig.json"), TSCONFIG)
        val app = project.resolve("App.vue")
        Files.writeString(app, APP_VUE)
        service.use(SESSION, project.toString(), listOf(app.toString()))
        service.openDocument(SESSION, project.toString(), app.toString(), 1, APP_VUE)
        // The TypeScript server answers first; the Vue server, once it started, adds the built-in components.
        val labels =
            try {
                awaitLabels(app.toString(), line = 6, column = 3) { "KeepAlive" in it }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw IllegalStateException("No component proposed: ${report()}", timeout)
            }
        val servers = runBlocking { service.status(-1) }.servers.map { it.serverName }
        // The names of the script reach the Vue server through the client, from the TypeScript server.
        assertTrue("greeting" in labels, "completion: ${labels.take(10)}")
        assertTrue("vue-language-server" in servers, "servers: $servers")
        assertTrue("typescript-language-server" in servers, "servers: $servers")
    }

    // `vue` is the project's own: installed once in the cache, then linked into each test project.
    private fun vueInstalled(): java.nio.file.Path? {
        val home = cache.resolve("vue-project")
        val modules = home.resolve("node_modules")
        if (!Files.isDirectory(modules.resolve("vue"))) {
            Files.createDirectories(home)
            Files.writeString(home.resolve("package.json"), "{}")
            val install = ProcessBuilder("npm", "install", "--silent", "vue@3").directory(home.toFile())
            val exit = runCatching { install.redirectErrorStream(true).start().waitFor() }.getOrDefault(-1)
            if (exit != 0) println("SKIPPED: vue could not be installed")
        }
        return modules.takeIf { Files.isDirectory(it.resolve("vue")) }
    }

    @Test
    fun `in a project with vue, the HTML elements are proposed and the errors of the template are reported`() {
        if (!nodeAvailable()) return
        val modules = vueInstalled() ?: return
        Files.createSymbolicLink(project.resolve("node_modules"), modules)
        Files.writeString(project.resolve("package.json"), """{"dependencies": {"vue": "3"}}""")
        Files.writeString(project.resolve("tsconfig.json"), TSCONFIG)
        val app = project.resolve("App.vue")
        Files.writeString(app, TYPED_VUE)
        service.use(SESSION, project.toString(), listOf(app.toString()))
        service.openDocument(SESSION, project.toString(), app.toString(), 1, TYPED_VUE)
        val labels =
            try {
                awaitLabels(app.toString(), line = 6, column = 3) { "button" in it || "div" in it }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw IllegalStateException("No element proposed: ${report()}", timeout)
            }
        assertTrue("button" in labels || "div" in labels, "completion: ${labels.take(10)}")
        // What vue-tsc would report, without running it: the engine is the same, and the server publishes it.
        val messages = awaitMessages(app.toString()) { list -> list.any { it.contains("nope") } }
        assertTrue(messages.any { it.contains("nope") }, "problems: $messages\n${report()}")
    }

    private fun awaitMessages(file: String, enough: (List<String>) -> Boolean): List<String> =
        runBlocking {
            withTimeout(ANSWER_MS) {
                var messages = emptyList<String>()
                while (!enough(messages)) {
                    delay(500)
                    val snapshot = service.intelligence.diagnostics(SESSION, -1)
                    val mine = snapshot.files.filter { it.path == file }
                    messages = mine.flatMap { f -> f.diagnostics.map { it.message } }
                }
                messages
            }
        }

    private fun awaitLabels(file: String, line: Int, column: Int, enough: (List<String>) -> Boolean): List<String> =
        runBlocking {
            withTimeout(ANSWER_MS) {
                var labels = labelsAt(file, line, column)
                while (!enough(labels)) {
                    delay(500)
                    labels = labelsAt(file, line, column)
                }
                labels
            }
        }

    // The state and the log of every server, for a failure.
    private fun report(): String {
        val servers = runBlocking { service.status(-1) }.servers
        return servers.joinToString("\n") {
            "${it.serverName} ${it.state} ${it.message}\n${service.log(it.id).joinToString("\n")}"
        }
    }

    private suspend fun labelsAt(file: String, line: Int, column: Int): List<String> {
        val answer = service.complete(SESSION, file, 1, line, column)
        return if (answer is CompletionResult) answer.items.map { it.label } else emptyList()
    }
}
