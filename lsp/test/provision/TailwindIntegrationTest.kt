package org.example.kotlineditor.lsp.provision

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.lsp.LanguageServerService
import org.example.kotlineditor.lsp.LanguageServerSettings
import org.example.kotlineditor.lsp.supervisor.SupervisorTimings
import org.example.kotlineditor.model.CompletionResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val ANSWER_MS = 120_000L
private const val SESSION = "s1"
private const val TAILWIND_CONFIG = "module.exports = { content: ['./**/*.tsx'], theme: { extend: {} }, plugins: [] }"
private const val VIEW = """export const V = () => <div className="fl">x</div>""" + "\n"
private const val AFTER_FL = 41

/**
 * The real Tailwind CSS server beside the TypeScript one, downloaded at their pins and run by the real supervisor:
 * a class name typed in a `.tsx` of a project that uses Tailwind is completed by Tailwind (TypeScript has nothing to
 * say there). Skipped when Node.js or npm is missing, or `tailwindcss` cannot be installed (no network).
 */
class TailwindIntegrationTest {
    private val project = createTempDirectory("tailwind-it")
    private val data = createTempDirectory("tailwind-it-data")

    // Kept between runs, so that the packages are downloaded once.
    private val cache = Paths.get(System.getProperty("user.home"), ".cache", "kotlin-editor", "test-tools")

    private val service by lazy {
        LanguageServerService(
            LanguageServerSettings(
                dataDirectory = data,
                toolsDirectory = cache,
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
        val node = WebServers.tailwindLanguageServer.requirements.firstOrNull()
        val detected = node?.let { RuntimeDetector().detect(it.runtime, it.minimumVersion, it.extensions) }
        if (detected !is RuntimeStatus.Found) println("SKIPPED: no Node.js for Tailwind CSS ($detected)")
        return detected is RuntimeStatus.Found
    }

    // tailwindcss is the project's own: installed once in the cache, then linked into each test project.
    private fun tailwindInstalled(): Path? {
        val home = cache.resolve("tailwind-project")
        val installed = home.resolve("node_modules").resolve("tailwindcss")
        if (!Files.isDirectory(installed)) {
            Files.createDirectories(home)
            Files.writeString(home.resolve("package.json"), "{}")
            val install = ProcessBuilder("npm", "install", "--silent", "tailwindcss@3").directory(home.toFile())
            val exit = runCatching { install.redirectErrorStream(true).start().waitFor() }.getOrDefault(-1)
            if (exit != 0) println("SKIPPED: tailwindcss could not be installed")
        }
        return home.resolve("node_modules").takeIf { Files.isDirectory(installed) }
    }

    @Test
    fun `a class name typed in a tsx is completed by the Tailwind server`() {
        if (!nodeAvailable()) return
        val modules = tailwindInstalled() ?: return
        Files.createSymbolicLink(project.resolve("node_modules"), modules)
        Files.writeString(project.resolve("package.json"), """{"devDependencies": {"tailwindcss": "3"}}""")
        Files.writeString(project.resolve("tailwind.config.js"), TAILWIND_CONFIG)
        Files.writeString(project.resolve("tsconfig.json"), """{"compilerOptions": {"jsx": "preserve"}}""")
        val view = project.resolve("view.tsx")
        Files.writeString(view, VIEW)
        service.use(SESSION, project.toString(), listOf(view.toString()))
        service.openDocument(SESSION, project.toString(), view.toString(), 1, VIEW)
        val labels =
            runBlocking {
                withTimeout(ANSWER_MS) {
                    var answer = service.complete(SESSION, view.toString(), 1, 0, AFTER_FL)
                    while (answer !is CompletionResult || answer.items.isEmpty()) {
                        delay(500)
                        answer = service.complete(SESSION, view.toString(), 1, 0, AFTER_FL)
                    }
                    answer.items.map { it.label }
                }
            }
        val servers = runBlocking { service.status(-1) }.servers.map { it.serverName }
        assertTrue(labels.any { it.startsWith("flex") || it.startsWith("first") }, "completion: ${labels.take(10)}")
        assertTrue("typescript-language-server" in servers, "servers: $servers")
        assertTrue("tailwindcss-language-server" in servers, "servers: $servers")
    }
}
