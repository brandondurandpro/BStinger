package org.example.kotlineditor.lsp.provision

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.lsp.LanguageServerService
import org.example.kotlineditor.lsp.LanguageServerSettings
import org.example.kotlineditor.lsp.supervisor.SupervisorTimings
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.model.LanguageServerState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val START_MS = 120_000L
private const val SESSION = "s1"

private val GREETER =
    listOf(
        "<?php",
        "class Greeter",
        "{",
        "    public function greet(string \$name): string",
        "    {",
        "        return \"Hello \" . \$name;",
        "    }",
        "}",
        "",
    ).joinToString("\n")

private val MAIN =
    listOf(
        "<?php",
        "\$greeter = new Greeter();",
        "echo \$greeter->greet(\"world\");",
        "",
    ).joinToString("\n")

/**
 * The real phpactor, downloaded at its pin and run by the real supervisor on the PHP of this machine: it must
 * start, stay up and answer. Skipped when no PHP recent enough is installed (the CI runners have none); set
 * `KOTLIN_EDITOR_PHPACTOR_PROJECT` to also start it on a real project of yours.
 */
class PhpactorIntegrationTest {
    private val project = createTempDirectory("phpactor-it")
    private val data = createTempDirectory("phpactor-it-data")

    // Kept between runs, so that the phar is downloaded once.
    private val tools = Paths.get(System.getProperty("user.home"), ".cache", "kotlin-editor", "test-tools")

    private val service by lazy {
        Files.writeString(data.resolve("language-servers.properties"), "php=phpactor\n")
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

    private fun phpAvailable(): Boolean {
        val php = PhpServers.phpactor.requirements.firstOrNull()
        val detected = php?.let { RuntimeDetector().detect(it.runtime, it.minimumVersion, it.extensions) }
        if (detected !is RuntimeStatus.Found) println("SKIPPED: no PHP for phpactor ($detected)")
        return detected is RuntimeStatus.Found
    }

    @Test
    fun `phpactor starts on a small project, stays up and answers a hover`() {
        if (!phpAvailable()) return
        Files.writeString(project.resolve("composer.json"), "{}\n")
        Files.writeString(project.resolve("Greeter.php"), GREETER)
        val main = project.resolve("main.php")
        Files.writeString(main, MAIN)
        startOn(project, main)
        val hover =
            runBlocking {
                withTimeout(START_MS) {
                    var answer = service.intelligence.hover(SESSION, DocumentPosition(main.toString(), 1, 2, 17))
                    while (answer.status == FeatureStatus.NotReady) {
                        delay(100)
                        answer = service.intelligence.hover(SESSION, DocumentPosition(main.toString(), 1, 2, 17))
                    }
                    answer
                }
            }
        val documented = hover.markdown.orEmpty().contains("greet")
        assertTrue(documented, "hover: $hover\n${logOf(server())}")
        stillUpAfter(project)
    }

    @Test
    fun `phpactor starts on the project given by KOTLIN_EDITOR_PHPACTOR_PROJECT`() {
        val given = System.getenv("KOTLIN_EDITOR_PHPACTOR_PROJECT")
        if (given.isNullOrBlank() || !phpAvailable()) return
        val root = Paths.get(given)
        val file =
            Files.walk(root).use { paths ->
                paths
                    .filter { it.toString().endsWith(".php") && !it.toString().contains("/vendor/") }
                    .findFirst()
                    .orElse(null)
            }
        checkNotNull(file) { "No PHP file under $root" }
        startOn(root, file)
        stillUpAfter(root)
    }

    private fun startOn(root: Path, file: Path) {
        service.use(SESSION, root.toString(), listOf(file.toString()))
        service.openDocument(SESSION, root.toString(), file.toString(), 1, Files.readString(file))
        val state = awaitSettled()
        assertTrue(state.state in UP, "phpactor ${state.state}: ${state.message}\n${logOf(state)}")
    }

    // phpactor crashed while indexing or on the documents it was sent once it had answered initialize.
    private fun stillUpAfter(root: Path) {
        Thread.sleep(UP_FOR_MS)
        val after = server()
        assertTrue(after.state in UP, "phpactor on $root ${after.state}: ${after.message}\n${logOf(after)}")
    }

    private fun awaitSettled(): LanguageServerInfo {
        val deadline = System.currentTimeMillis() + START_MS
        var current = serverOrNull()
        while (!isSettled(current)) {
            val waited = current?.let { "phpactor still ${it.state}\n${logOf(it)}" } ?: "No language server"
            check(System.currentTimeMillis() < deadline) { waited }
            Thread.sleep(50)
            current = serverOrNull()
        }
        return checkNotNull(current)
    }

    private fun isSettled(info: LanguageServerInfo?): Boolean {
        val state = info?.state ?: return false
        return state in UP || state in DOWN
    }

    private fun serverOrNull(): LanguageServerInfo? = runBlocking { service.status(-1) }.servers.firstOrNull()

    private fun server(): LanguageServerInfo = checkNotNull(serverOrNull()) { "No language server" }

    private fun logOf(info: LanguageServerInfo) = service.log(info.id).joinToString("\n")

    private companion object {
        const val UP_FOR_MS = 5_000L
        val UP = setOf(LanguageServerState.Running, LanguageServerState.Indexing)
        val DOWN =
            setOf(
                LanguageServerState.Crashed,
                LanguageServerState.Restarting,
                LanguageServerState.Unavailable,
                LanguageServerState.Stopped,
            )
    }
}
