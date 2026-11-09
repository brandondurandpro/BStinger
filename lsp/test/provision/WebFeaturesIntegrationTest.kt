package org.example.kotlineditor.lsp.provision

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.lsp.LanguageServerService
import org.example.kotlineditor.lsp.LanguageServerSettings
import org.example.kotlineditor.lsp.supervisor.SupervisorTimings
import org.example.kotlineditor.model.CompletionResult
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.NavigationKind
import org.example.kotlineditor.model.UsageCountsResult
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ANSWER_MS = 120_000L
private const val SESSION = "web"
private const val TSCONFIG =
    """{"compilerOptions": {"strict": true, "moduleResolution": "bundler", "module": "esnext"}}"""
private const val MANY_FUNCTIONS = 200

/**
 * The real typescript-language-server, with the Vue plugin for `.vue` files, through the service the IDE uses: member
 * completion after a dot, the type that comes with a resolved item, the usages counted with their count, and a file
 * with many declarations. Skipped when no Node.js recent enough is installed.
 */
class WebFeaturesIntegrationTest {
    private val project = createTempDirectory("web-features-it")
    private val data = createTempDirectory("web-features-it-data")
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

    private fun open(name: String, text: String): String {
        val path = project.resolve(name)
        path.parent.toFile().mkdirs()
        Files.writeString(path, text)
        return path.toString()
    }

    private fun start(vararg paths: String) {
        Files.writeString(project.resolve("tsconfig.json"), TSCONFIG)
        service.use(SESSION, project.toString(), paths.toList())
    }

    // Asks again until [accept] likes the answer: the project loads for a few seconds after the server started.
    private fun <T> awaitAnswer(ask: suspend () -> T, accept: (T) -> Boolean): T =
        runBlocking {
            withTimeout(ANSWER_MS) {
                var answer = ask()
                while (!accept(answer)) {
                    delay(300)
                    answer = ask()
                }
                answer
            }
        }

    private fun completionAt(path: String, line: Int, column: Int): CompletionResult =
        awaitAnswer({ service.complete(SESSION, path, 1, line, column) }) { result ->
            result.ready && result.items.isNotEmpty()
        }

    @Test
    fun `members are proposed right after the dot, and the type of an item comes with its resolve`() {
        if (!nodeAvailable()) return
        val text = "const day = new Date()\nday.\n"
        val path = open("dates.ts", text)
        start(path)
        service.openDocument(SESSION, project.toString(), path, 1, text)
        val result = completionAt(path, 1, 4)
        val getDate = result.items.indexOfFirst { it.label == "getDate" }
        assertTrue(getDate >= 0, "getDate is proposed: ${result.items.take(10).map { it.label }}")
        assertTrue(result.token.isNotEmpty(), "the items can be resolved")
        val index = result.items[getDate].resolveIndex
        val resolved = runBlocking { service.resolveCompletion(SESSION, result.token, index) }.orEmpty()
        assertTrue(resolved.startsWith("```"), "the signature comes first, in a code block: $resolved")
        assertTrue(resolved.contains("getDate(): number"), "resolved: $resolved")
    }

    @Test
    fun `the hover of a constructor lists its overloads instead of counting them`() {
        if (!nodeAvailable()) return
        val text = "const day = new Date()\n"
        val path = open("overloads.ts", text)
        start(path)
        service.openDocument(SESSION, project.toString(), path, 1, text)
        val at = DocumentPosition(path, 1, 0, 16)
        val hover =
            awaitAnswer({ service.intelligence.hover(SESSION, at) }) { answer ->
                answer.status == FeatureStatus.Ready && answer.markdown != null
            }
        val markdown = hover.markdown.orEmpty()
        assertTrue(!markdown.contains("overloads)"), "no count left: $markdown")
        assertTrue(markdown.contains("new Date(value: number | string): Date"), "the others are listed: $markdown")
        assertTrue(markdown.contains("new Date(): Date"), "the first is listed too: $markdown")
    }

    @Test
    fun `a Vue script is completed like TypeScript`() {
        if (!nodeAvailable()) return
        val lines = listOf("<script setup lang='ts'>", "const name: string = 'a'", "name.", "</script>", "")
        val text = lines.joinToString("\n")
        val path = open("Greeting.vue", text)
        start(path)
        service.openDocument(SESSION, project.toString(), path, 1, text)
        val result = completionAt(path, 2, 5)
        val labels = result.items.map { it.label }
        assertTrue("toUpperCase" in labels, "members of string: $labels")
    }

    @Test
    fun `the usages of a declaration are counted, and listed from what was counted`() {
        if (!nodeAvailable()) return
        val shared = listOf("export function shared(): number {", "  return 1", "}", "").joinToString("\n")
        val sharedPath = open("shared.ts", shared)
        val user =
            listOf("import { shared } from './shared'", "export const a = shared()", "export const b = shared()", "")
                .joinToString("\n")
        val userPath = open("user.ts", user)
        start(sharedPath, userPath)
        service.openDocument(SESSION, project.toString(), userPath, 1, user)
        service.openDocument(SESSION, project.toString(), sharedPath, 1, shared)
        val counts: UsageCountsResult =
            awaitAnswer({ service.intelligence.usageCounts(SESSION, sharedPath, 1) }) { result ->
                result.status == FeatureStatus.Ready && result.counts.any { it.name == "shared" && it.count >= 3 }
            }
        val declaration = counts.counts.first { it.name == "shared" }
        // tsserver counts the import of the name besides its two calls.
        assertEquals(3, declaration.count, "usages: $counts")
        val position = DocumentPosition(sharedPath, 1, declaration.range.startLine, declaration.range.startColumn)
        val usages = runBlocking { service.intelligence.references(SESSION, position, false) }
        assertEquals(FeatureStatus.Ready, usages.status)
        assertEquals(declaration.count, usages.locations.size, "the list is the counted usages: $usages")
    }

    @Test
    fun `a file with many declarations gets its counts, not none`() {
        if (!nodeAvailable()) return
        val functions = (1..MANY_FUNCTIONS).joinToString("\n") { "export function f$it(): number { return $it }" }
        val path = open("many.ts", functions + "\n")
        start(path)
        service.openDocument(SESSION, project.toString(), path, 1, functions + "\n")
        val counts =
            awaitAnswer({ service.intelligence.usageCounts(SESSION, path, 1) }) { it.status == FeatureStatus.Ready }
        assertTrue(counts.counts.size >= 100, "counts of ${counts.counts.size} of $MANY_FUNCTIONS declarations")
    }

    @Test
    fun `go to definition crosses files, from a TypeScript file and from a Vue script`() {
        if (!nodeAvailable()) return
        val shared = listOf("export function shared(): number {", "  return 1", "}", "").joinToString("\n")
        val sharedPath = open("shared.ts", shared)
        val user = listOf("import { shared } from './shared'", "export const a = shared()", "").joinToString("\n")
        val userPath = open("user.ts", user)
        val vue = listOf("<script setup lang='ts'>", "import { shared } from './shared'", "shared()", "</script>", "")
        val vuePath = open("Use.vue", vue.joinToString("\n"))
        start(sharedPath, userPath, vuePath)
        service.openDocument(SESSION, project.toString(), userPath, 1, user)
        service.openDocument(SESSION, project.toString(), vuePath, 1, vue.joinToString("\n"))
        for ((path, line, column) in listOf(Triple(userPath, 1, 19), Triple(vuePath, 2, 2))) {
            val at = DocumentPosition(path, 1, line, column)
            val found =
                awaitAnswer({ service.intelligence.navigate(SESSION, at, NavigationKind.Definition) }) {
                    it.status == FeatureStatus.Ready && it.locations.isNotEmpty()
                }
            val target = Paths.get(found.locations.first().path).fileName.toString()
            assertEquals("shared.ts", target, "definition from $path: $found")
        }
    }

    @Test
    fun `a type error is reported in a TypeScript file and in a Vue script`() {
        if (!nodeAvailable()) return
        val ts = "export const n: number = 'text'\n"
        val tsPath = open("wrong.ts", ts)
        val vue = listOf("<script setup lang='ts'>", "const s: string = 1", "</script>", "").joinToString("\n")
        val vuePath = open("Wrong.vue", vue)
        start(tsPath, vuePath)
        service.openDocument(SESSION, project.toString(), tsPath, 1, ts)
        service.openDocument(SESSION, project.toString(), vuePath, 1, vue)
        val files =
            awaitAnswer({ service.intelligence.diagnostics(SESSION, -1).files }) { found ->
                found.count { it.diagnostics.isNotEmpty() } == 2
            }
        val messages = files.flatMap { it.diagnostics }.map { it.message }
        assertEquals(2, messages.count { it.contains("not assignable") }, "diagnostics: $messages")
    }
}
