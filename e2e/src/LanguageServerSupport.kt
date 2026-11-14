package org.example.kotlineditor.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import org.example.kotlineditor.lsp.LanguageServerSettings
import org.example.kotlineditor.lsp.provision.CandidateRegistry
import org.example.kotlineditor.lsp.provision.LanguageServerCandidate
import org.example.kotlineditor.lsp.provision.PhpServers
import org.example.kotlineditor.lsp.provision.Requirement
import org.example.kotlineditor.lsp.provision.RuntimeDetector
import org.example.kotlineditor.lsp.provision.RuntimeKind
import org.example.kotlineditor.lsp.supervisor.SupervisorTimings
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.model.ProjectCheckResult
import org.example.kotlineditor.testkit.lsp.FakeLanguageServerCommand
import java.nio.file.Files
import java.nio.file.Path

/** How long a language server may take to start: a new JVM. */
const val SERVER_START_MS = 60_000L

/**
 * The PHP language servers of an e2e test: `fake`, a [org.example.kotlineditor.testkit.lsp.FakeLanguageServer]
 * started with [options], and `needs-node`, which needs Node.js and finds none; with [alternative], also `other`,
 * the same fake server under another name, to switch to.
 */
class FakePhpServers(
    vararg options: String,
    tailwindOptions: Array<String> = emptyArray(),
    idleStopMs: Long = 1_000,
    alternative: Boolean = false,
    check: ((project: String) -> ProjectCheckResult)? = null,
) {
    /** Choices and pids of the servers. */
    val data: Path = Files.createTempDirectory("kotlin-editor-lsp")
    private val fake =
        LanguageServerCandidate(
            id = FAKE,
            displayName = "fakephp",
            language = "php",
            licence = "Test only",
            strengths = listOf("Starts in a second"),
            drawbacks = listOf("Knows nothing about PHP"),
            requirements = emptyList(),
            tool = null,
            command = { _, _ -> FakeLanguageServerCommand.command(*options) },
            // Configured as phpactor is: its inlay hints on, types included.
            initializationOptions = PhpServers.PHPACTOR_OPTIONS,
            withoutInlayHints = WITHOUT_INLAY_HINTS,
            homepage = FAKE_HOMEPAGE,
        )
    private val needsNode =
        fake.copy(
            id = NEEDS_NODE,
            displayName = "nodephp",
            requirements = listOf(Requirement(RuntimeKind.Node, "18")),
        )

    private val other = fake.copy(id = OTHER, displayName = "otherphp")

    // The only server of TypeScript and JavaScript, as typescript-language-server is: no choice to make.
    private val typescript =
        fake.copy(
            id = FAKE_TYPESCRIPT,
            displayName = "fakets",
            language = "typescript",
            strengths = listOf("Starts in a second"),
            drawbacks = listOf("Knows nothing about TypeScript"),
            initializationOptions = null,
            withoutInlayHints = null,
        )

    // The Vue server beside it, for .vue files.
    private val vue = typescript.copy(id = FAKE_VUE, displayName = "fakevue", language = "vue")

    // Tailwind CSS beside it, for the projects that have a tailwind.config.js.
    private val tailwind =
        typescript.copy(
            id = FAKE_TAILWIND,
            displayName = "faketailwind",
            language = "tailwind",
            // It knows only class names.
            command = { _, _ ->
                FakeLanguageServerCommand.command(*options, *tailwindOptions, "--only-items", TAILWIND_CLASSES)
            },
        )

    // No PHP nor Node.js: the fake server needs neither, and the other candidate is unavailable.
    private val nothingInstalled =
        RuntimeDetector(pathEnvironment = "", extraDirectories = { emptyList() }, probe = { _, _ -> null })

    private val timings =
        SupervisorTimings(
            idleStopMs = idleStopMs,
            initializeTimeoutMs = 20_000,
            shutdownTimeoutMs = 1_000,
            firstBackoffMs = 100,
        )

    /** What the server of the test uses. */
    val settings =
        LanguageServerSettings(
            dataDirectory = data,
            toolsDirectory = data.resolve("tools"),
            candidates =
            CandidateRegistry(
                nothingInstalled,
                listOfNotNull(fake, needsNode, other.takeIf { alternative }, typescript, vue, tailwind),
            ),
            timings = timings,
            projectCheck = check,
            statusHoldMs = 5_000,
            sampleIntervalMs = 250,
        )

    /** Saves [id] as the chosen PHP server, as a previous session would have. */
    fun chosen(id: String = FAKE): FakePhpServers {
        Files.writeString(data.resolve("language-servers.properties"), "php=$id\n")
        return this
    }

    /** What is saved on disk. */
    fun saved(): String = runCatching { Files.readString(data.resolve("language-servers.properties")) }.getOrDefault("")

    fun delete() = data.toFile().deleteRecursively()

    companion object {
        const val FAKE = "fake"

        /** The GitHub page of `fake` (and of the servers made from it), shown as a link. */
        const val FAKE_HOMEPAGE = "https://github.com/example/fakephp"
        const val NEEDS_NODE = "needs-node"
        const val OTHER = "other"
        const val FAKE_TYPESCRIPT = "fake-ts"
        const val FAKE_TAILWIND = "fake-tailwind"
        const val FAKE_VUE = "fake-vue"

        /** The class names the fake Tailwind CSS server proposes, comma-separated. */
        const val TAILWIND_CLASSES = "twflex,twgrid"

        /** Why the fake server gives no inlay hints, when started `--without inlayHint`. */
        const val WITHOUT_INLAY_HINTS = "fakephp gives inlay hints only with a licence."

        /** A web project: `package.json` and `tsconfig.json` at its root, TypeScript, React and JavaScript files. */
        fun webProject(): SampleProject =
            SampleProject().apply {
                file("package.json", "{}\n")
                file("tsconfig.json", "{}\n")
                file("src/app.ts", "export const answer: number = 42\n")
                file("src/view.tsx", "export const View = () => <p>hello</p>\n")
                file("src/legacy.js", "module.exports = 1\n")
                file("src/App.vue", """<script setup lang="ts">\nconst hello = 1\n</script>\n""")
                file("README.md", "# Shop\n")
            }

        /** A web project that uses Tailwind CSS: [webProject] with a `tailwind.config.js`. */
        fun tailwindProject(): SampleProject =
            webProject().apply { file("tailwind.config.js", "module.exports = { content: ['./src/**/*.tsx'] }\n") }

        /** A PHP project: a `composer.json` at its root and two PHP files. */
        fun project(): SampleProject =
            SampleProject().apply {
                file("composer.json", "{}\n")
                file("src/index.php", "<?php\necho 'Hello';\n")
                file("src/util.php", "<?php\nfunction shout(string \$s): string { return strtoupper(\$s); }\n")
                file("README.md", "# Shop\n")
            }
    }
}

/** The language servers of the app's status, as the server last sent them. */
val E2eApp.languageServers: List<LanguageServerInfo> get() = state.session.languageServers.status.servers

/** Waits for a language server matching [predicate], and returns it. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitServer(
    app: E2eApp,
    timeout: Long = SERVER_START_MS,
    predicate: (LanguageServerInfo) -> Boolean,
): LanguageServerInfo {
    try {
        awaitCondition(timeout) { app.languageServers.any(predicate) }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("No such language server in ${app.languageServers}", e)
    }
    return app.languageServers.first(predicate)
}

/** Text shown by the node tagged [tag] and the nodes inside it, one line each. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.textOf(tag: String): String =
    onAllNodes(hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() }
        .joinToString("\n") { it.text }

/** Whether this server is running. */
fun LanguageServerInfo.isRunning() = state == org.example.kotlineditor.model.LanguageServerState.Running

/** Whether process [pid] still runs. */
fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
