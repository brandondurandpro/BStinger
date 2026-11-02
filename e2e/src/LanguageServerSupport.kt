package org.example.kotlineditor.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import org.example.kotlineditor.lsp.LanguageServerSettings
import org.example.kotlineditor.lsp.provision.CandidateRegistry
import org.example.kotlineditor.lsp.provision.LanguageServerCandidate
import org.example.kotlineditor.lsp.provision.Requirement
import org.example.kotlineditor.lsp.provision.RuntimeDetector
import org.example.kotlineditor.lsp.provision.RuntimeKind
import org.example.kotlineditor.lsp.supervisor.SupervisorTimings
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.testkit.lsp.FakeLanguageServerCommand
import java.nio.file.Files
import java.nio.file.Path

/** How long a language server may take to start: a new JVM. */
const val SERVER_START_MS = 60_000L

/**
 * The PHP language servers of an e2e test: `fake`, a [org.example.kotlineditor.testkit.lsp.FakeLanguageServer]
 * started with [options], and `needs-node`, which needs Node.js and finds none.
 */
class FakePhpServers(vararg options: String, idleStopMs: Long = 1_000) {
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
        )
    private val needsNode =
        fake.copy(
            id = NEEDS_NODE,
            displayName = "nodephp",
            requirements = listOf(Requirement(RuntimeKind.Node, "18")),
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
            candidates = CandidateRegistry(nothingInstalled, listOf(fake, needsNode)),
            timings = timings,
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
        const val NEEDS_NODE = "needs-node"

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
val E2eApp.languageServers: List<LanguageServerInfo> get() = state.languageServers.status.servers

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
