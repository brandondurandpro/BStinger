package org.example.kotlineditor.lsp.provision

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.lsp.LanguageServerService
import org.example.kotlineditor.lsp.LanguageServerSettings
import org.example.kotlineditor.lsp.supervisor.SupervisorTimings
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.LanguageServerState
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val ANSWER_MS = 120_000L
private const val SESSION = "s1"

private const val TSCONFIG = """{"compilerOptions": {"jsx": "preserve", "strict": true}}"""

private val APP_VUE =
    listOf(
        "<script setup lang=\"ts\">",
        "const greeting: string = 'hello'",
        "</script>",
        "",
        "<template>",
        "  <p>{{ greeting }}</p>",
        "</template>",
        "",
    ).joinToString("\n")

private val GREETER =
    listOf(
        "export function greet(name: string): string {",
        "  return 'Hello ' + name",
        "}",
        "",
    ).joinToString("\n")

// A .tsx: the server is told `typescriptreact`, or it would not parse the JSX.
private val VIEW =
    listOf(
        "import { greet } from './greeter'",
        "export const View = () => <p>{greet('world')}</p>",
        "",
    ).joinToString("\n")

/**
 * The real typescript-language-server, downloaded at its pin with its `typescript` and run by the real supervisor on
 * the Node.js of this machine: it must start by itself (it is the only server of its language) and answer a hover
 * in a `.tsx`. Skipped when no Node.js recent enough is installed.
 */
class TypeScriptIntegrationTest {
    private val project = createTempDirectory("typescript-it")
    private val data = createTempDirectory("typescript-it-data")

    // Kept between runs, so that the packages are downloaded once.
    private val tools = Paths.get(System.getProperty("user.home"), ".cache", "kotlin-editor", "test-tools")

    private val service by lazy {
        LanguageServerService(
            LanguageServerSettings(
                dataDirectory = data,
                toolsDirectory = tools,
                timings = SupervisorTimings(initializeTimeoutMs = 60_000, firstBackoffMs = 200),
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
        val node = WebServers.typescriptLanguageServer.requirements.firstOrNull()
        val detected = node?.let { RuntimeDetector().detect(it.runtime, it.minimumVersion, it.extensions) }
        if (detected !is RuntimeStatus.Found) println("SKIPPED: no Node.js for typescript-language-server ($detected)")
        return detected is RuntimeStatus.Found
    }

    @Test
    fun `typescript-language-server starts alone on a project and tells the type of a component written in a tsx`() {
        if (!nodeAvailable()) return
        Files.writeString(project.resolve("tsconfig.json"), TSCONFIG)
        Files.writeString(project.resolve("greeter.ts"), GREETER)
        val view = project.resolve("view.tsx")
        Files.writeString(view, VIEW)
        service.use(SESSION, project.toString(), listOf(view.toString()))
        service.openDocument(SESSION, project.toString(), view.toString(), 1, VIEW)
        val hover =
            try {
                awaitHover(DocumentPosition(view.toString(), 1, 1, 14))
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                val servers = runBlocking { service.status(-1) }.servers
                error("No hover: $servers\n${log()}\n${timeout.message}")
            }
        val typed = hover.markdown.orEmpty()
        assertTrue(typed.contains("View") && typed.contains("=>"), "hover: $hover\n${log()}")
        val server = runBlocking { service.status(-1) }.servers.firstOrNull()
        assertTrue(server?.state in UP, "server: $server\n${log()}")
    }

    @Test
    fun `a single-file component is understood through the Vue plugin, its template too`() {
        if (!nodeAvailable()) return
        Files.writeString(project.resolve("tsconfig.json"), TSCONFIG)
        val app = project.resolve("App.vue")
        Files.writeString(app, APP_VUE)
        service.use(SESSION, project.toString(), listOf(app.toString()))
        service.openDocument(SESSION, project.toString(), app.toString(), 1, APP_VUE)
        // `greeting` in the template: only the plugin makes tsserver see the script it comes from.
        val hover =
            try {
                awaitHover(DocumentPosition(app.toString(), 1, 5, 10))
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                error("No hover in the template: ${timeout.message}\n${log()}")
            }
        val typed = hover.markdown.orEmpty()
        // The template's own view of `greeting`, a property of the component (its type needs `vue` in the project).
        assertTrue(typed.contains("(property) greeting"), "hover: $hover\n${log()}")
    }

    private fun awaitHover(position: DocumentPosition) =
        runBlocking {
            withTimeout(ANSWER_MS) {
                var answer = service.intelligence.hover(SESSION, position)
                while (answer.status == FeatureStatus.NotReady || answer.markdown.isNullOrBlank()) {
                    delay(200)
                    answer = service.intelligence.hover(SESSION, position)
                }
                answer
            }
        }

    private fun log(): String {
        val id = runBlocking { service.status(-1) }.servers.firstOrNull()?.id
        return id?.let { service.log(it).joinToString("\n") }.orEmpty()
    }

    private companion object {
        val UP = setOf(LanguageServerState.Running, LanguageServerState.Indexing)
    }
}
