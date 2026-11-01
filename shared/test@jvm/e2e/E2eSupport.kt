package org.example.kotlineditor.e2e

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.printToString
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.App
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.rpc.ClientOptions
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.rpc.appServices
import org.example.kotlineditor.server.EditorServer
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceStore
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.Screen
import org.example.kotlineditor.syntax.treesitter.GrammarStore
import org.example.kotlineditor.testkit.ChaosProxy
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import org.example.kotlineditor.ui.TextClipboard
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

const val TIMEOUT = 10_000L

/** Client tuning of chaos tests: a silent link is noticed within a second, reconnections are quick. */
val CHAOS_CLIENT_OPTIONS = ClientOptions(heartbeatMs = 200, deadAfterMs = 1_000, maxBackoffMs = 200)

/** A throw-away project on disk. */
class SampleProject(val root: Path = Files.createTempDirectory("kotlin-editor-e2e")) {
    val path: String get() = root.toString()

    fun file(relative: String, content: String): Path {
        val p = root.resolve(relative)
        p.parent.createDirectories()
        p.writeText(content)
        return p
    }

    fun binary(relative: String, bytes: ByteArray): Path {
        val p = root.resolve(relative)
        p.parent.createDirectories()
        p.writeBytes(bytes)
        return p
    }

    fun dir(relative: String): Path = root.resolve(relative).createDirectories()

    fun read(relative: String): String = Files.readString(root.resolve(relative))

    fun delete() = root.toFile().deleteRecursively()

    companion object {
        val APP_KT =
            """
            package demo

            import demo.util.shout

            /* Entry point
               of the demo */
            fun main() {
                val greeting = "Hello"
                println(greeting.shout())
            }
            """.trimIndent()

        fun standard(): SampleProject =
            SampleProject().apply {
                file("README.md", textOf("# Demo", "", "A sample project.", ""))
                file("build.gradle.kts", textOf("plugins {", """    kotlin("jvm")""", "}", ""))
                file("src/main/kotlin/App.kt", APP_KT)
                file(
                    "src/main/kotlin/util/Strings.kt",
                    textOf("package demo.util", "", """fun String.shout() = uppercase() + "!"""", ""),
                )
                file(
                    "src/test/kotlin/AppTest.kt",
                    textOf("package demo", "", "class AppTest {", "    fun greetingIsLoud() {}", "}", ""),
                )
                file("docs/guide.md", "Guide\nHello from the guide\n")
                file(".git/config", "[core]\n")
                binary("logo.png", byteArrayOf(-119, 80, 78, 71, 0, 0, 0, 13, 0, 1))
            }
    }
}

/**
 * The server process of an e2e app, which a chaos test can stop and start again on the same port and data, as when
 * it crashes or is redeployed: its sessions (shells, cached answers) are lost, the files and stores are not.
 */
class ServerProcess(private val data: java.io.File) : AutoCloseable {
    var server: EditorServer = EditorServer(LocalServices(data)).start()
        private set
    val port: Int = server.port
    private var running = true

    /** Kills the server: connections drop and new ones are refused until [start]. */
    fun stop() {
        server.close()
        running = false
    }

    /** Starts a new server on the same port and data, presenting [token] (the same one by default). */
    fun start(token: String = server.token) {
        check(!running) { "The server is already running" }
        server = EditorServer(LocalServices(data), token).start(port = port)
        running = true
    }

    /** Stops then starts the server, like a crash followed by an automatic restart. */
    fun restart(token: String = server.token) {
        stop()
        start(token)
    }

    override fun close() {
        if (running) server.close()
    }
}

class E2eApp(
    val state: AppState,
    val recentStore: org.example.kotlineditor.model.RecentProjectsStore,
    val picker: FakePicker,
    val process: ServerProcess,
    val client: RpcClient,
    private val proxy: ChaosProxy?,
) {
    val server: EditorServer get() = process.server

    /** The network between the UI and the server, for an app launched with `chaos = true`. */
    val network: ChaosProxy get() = checkNotNull(proxy) { "Launch the app with chaos = true" }

    val recentPaths: List<String> get() = runBlocking { recentStore.load() }

    val project: ProjectState get() = (state.screen as Screen.Project).project
}

class FakePicker(var next: String? = null) : org.example.kotlineditor.model.DirectoryPicker {
    var calls = 0

    override suspend fun pickDirectory(): String? {
        calls++
        return next
    }
}

/**
 * Starts the app as in production: a real [EditorServer] (data in a temporary directory) and the UI talking to
 * it through the network client. Everything the e2e tests do therefore goes through the protocol.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.launchApp(
    recent: List<String> = emptyList(),
    pick: String? = null,
    sessions: org.example.kotlineditor.model.SessionStore? = null,
    preferences: PreferenceStore = InMemoryPreferenceStore(),
    chaos: Boolean = false,
    grammars: GrammarStore? = TestGrammars.shared,
): E2eApp {
    TextClipboard.useSystem = false
    val data = Files.createTempDirectory("kotlin-editor-server").toFile()
    val process = ServerProcess(data)
    val token = process.server.token
    // Chaos tests go through a proxy that breaks the network, and notice a dead link within a second.
    val proxy = if (chaos) ChaosProxy(process.port) else null
    val client =
        if (proxy == null) {
            RpcClient("127.0.0.1", process.port, token)
        } else {
            RpcClient("127.0.0.1", proxy.port, token, CHAOS_CLIENT_OPTIONS)
        }
    Runtime.getRuntime().addShutdownHook(Thread { data.deleteRecursively() })
    runBlocking { client.awaitConnected() }
    val remote = client.appServices(FakePicker(pick))
    if (recent.isNotEmpty()) runBlocking { remote.recentProjects.save(recent) }
    val services =
        AppServices(
            remote.fileSystem,
            remote.search,
            remote.recentProjects,
            remote.directoryPicker,
            remote.terminal,
            sessions ?: remote.sessions,
            remote.git,
            remote.connection,
            remote.network,
        )
    var created: AppState? = null
    setContent {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(services, scope, preferences, grammars) }
        created = state
        DisposableEffect(Unit) {
            onDispose {
                client.close()
                proxy?.close()
                process.close()
            }
        }
        App(state)
    }
    waitForIdle()
    if (recent.isNotEmpty()) waitUntil(timeoutMillis = TIMEOUT) { created!!.recentProjects.size == recent.size }
    return E2eApp(created!!, services.recentProjects, services.directoryPicker as FakePicker, process, client, proxy)
}

/** Opens [project] through the welcome screen, as a user would, and waits for its tree. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.launchWithProject(
    project: SampleProject,
    preferences: PreferenceStore = InMemoryPreferenceStore(),
    chaos: Boolean = false,
    grammars: GrammarStore? = TestGrammars.shared,
): E2eApp {
    val app = launchApp(pick = project.path, preferences = preferences, chaos = chaos, grammars = grammars)
    onNodeWithTag(Tags.WELCOME_OPEN).performClick()
    awaitTag(Tags.treeRow("src"))
    return app
}

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitTag(tag: String, timeout: Long = TIMEOUT, unmerged: Boolean = false): SemanticsNodeInteraction {
    try {
        waitUntil(timeoutMillis = timeout) { onAllNodes(hasTestTag(tag), unmerged).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("No node tagged '$tag'. UI tree:\n" + onRoot().printToString(maxDepth = 12), e)
    }
    return onNodeWithTag(tag, unmerged)
}

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitNoTag(tag: String, timeout: Long = TIMEOUT) {
    waitUntil(timeoutMillis = timeout) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }
}

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitCondition(timeout: Long = TIMEOUT, condition: () -> Boolean) {
    waitUntil(timeoutMillis = timeout) { condition() }
}

@OptIn(ExperimentalTestApi::class)
fun SemanticsNodeInteraction.performDoubleClick(): SemanticsNodeInteraction = performMouseInput { doubleClick() }

/** Expands directories then double-clicks [relativePath] in the project tree, and waits for its editor. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openFromTree(relativePath: String) {
    val parts = relativePath.split('/')
    for (i in 1 until parts.size) {
        val dir = parts.subList(0, i).joinToString("/")
        val child = parts.subList(0, i + 1).joinToString("/")
        if (onAllNodes(hasTestTag(Tags.treeRow(child))).fetchSemanticsNodes().isEmpty()) {
            awaitTag(Tags.treeChevron(dir)).performClick()
            awaitTag(Tags.treeRow(child))
        }
    }
    onNodeWithTag(Tags.treeRow(relativePath)).performDoubleClick()
    awaitTag(Tags.tab(relativePath))
    awaitEditorReady()
}

/** Waits until the active tab finished loading (its content or a message is shown). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitEditorReady() {
    waitUntil(timeoutMillis = TIMEOUT) {
        onAllNodes(hasTestTag(Tags.EDITOR) or hasTestTag(Tags.EDITOR_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
    }
}

/** Lines joined with `\n`, to write multi-line file contents without escapes. */
fun textOf(vararg lines: String): String = lines.joinToString("\n")

/** Matches the nodes whose test tag starts with [prefix]. */
fun hasTestTagPrefix(prefix: String) =
    androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") { node ->
        node.config
            .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }
            ?.startsWith(prefix) == true
    }

/** Opens the context menu of the node tagged [tag]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.rightClickOn(tag: String) {
    onNodeWithTag(tag).performMouseInput {
        moveTo(center)
        press(androidx.compose.ui.test.MouseButton.Secondary)
        release(androidx.compose.ui.test.MouseButton.Secondary)
    }
}

/** Waits until the status bar says the server is out of reach. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitReconnecting() {
    awaitCondition {
        onAllNodes(hasTestTag(Tags.STATUS_CONNECTION)).fetchSemanticsNodes().any { node ->
            node.config
                .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
                ?.any { "reconnecting" in it.text } == true
        }
    }
}

/** Waits until the status bar no longer warns about the server. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitReconnected() = awaitNoTag(Tags.STATUS_CONNECTION)

/** Opens [path] from the list of recent projects of the welcome screen, and waits for its tree. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openRecentFromWelcome(path: String) {
    awaitTag(ItemTags.recentProject(path)).performClick()
    awaitTag(Tags.PROJECT)
    awaitCondition { onAllNodes(hasTestTagPrefix("tree:")).fetchSemanticsNodes().isNotEmpty() }
}

/** Clicks the project name in the toolbar, then [path] among the recent projects of its menu. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.switchProjectTo(path: String) {
    onNodeWithTag(Tags.PROJECT_WIDGET).performClick()
    awaitTag(ItemTags.switchProject(path)).performClick()
    awaitNoTag(Tags.PROJECT_MENU)
}
