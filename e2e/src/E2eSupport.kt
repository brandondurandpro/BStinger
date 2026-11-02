package org.example.kotlineditor.e2e

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.requestFocus
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.App
import org.example.kotlineditor.lsp.LanguageServerSettings
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.remote.JvmRemoteFactory
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
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.RemoteTags
import org.example.kotlineditor.ui.TextClipboard
import org.example.kotlineditor.ui.TreeTags
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
class ServerProcess(private val data: java.io.File, private val languageServers: LanguageServerSettings? = null) :
    AutoCloseable {
    var server: EditorServer = EditorServer(services()).start()
        private set
    val port: Int = server.port
    private var running = true

    private fun services() =
        LocalServices(data, languageServerSettings = languageServers ?: LanguageServerSettings(data.toPath()))

    /** Kills the server: connections drop and new ones are refused until [start]. */
    fun stop() {
        server.close()
        running = false
    }

    /** Starts a new server on the same port and data, presenting [token] (the same one by default). */
    fun start(token: String = server.token) {
        check(!running) { "The server is already running" }
        server = EditorServer(services(), token).start(port = port)
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
    val process: ServerProcess,
    val client: RpcClient,
    private val proxy: ChaosProxy?,
    /** What the UI sent to the shells of the terminal. */
    val terminalInput: TerminalInputRecorder,
) {
    val server: EditorServer get() = process.server

    /** The network between the UI and the server, for an app launched with `chaos = true`. */
    val network: ChaosProxy get() = checkNotNull(proxy) { "Launch the app with chaos = true" }

    val recentPaths: List<String> get() = runBlocking { recentStore.load() }

    val project: ProjectState get() = (state.screen as Screen.Project).project
}

/**
 * Starts the app as in production: a real [EditorServer] (data in a temporary directory) and the UI talking to
 * it through the network client. Everything the e2e tests do therefore goes through the protocol.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.launchApp(
    recent: List<String> = emptyList(),
    sessions: org.example.kotlineditor.model.SessionStore? = null,
    preferences: PreferenceStore = InMemoryPreferenceStore(),
    chaos: Boolean = false,
    grammars: GrammarStore? = TestGrammars.shared,
    languageServers: LanguageServerSettings? = null,
): E2eApp {
    TextClipboard.useSystem = false
    val data = Files.createTempDirectory("kotlin-editor-server").toFile()
    val process = ServerProcess(data, languageServers)
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
    val remote = client.appServices()
    if (recent.isNotEmpty()) runBlocking { remote.recentProjects.save(recent) }
    val terminalInput = TerminalInputRecorder(remote.terminal)
    val services =
        AppServices(
            remote.fileSystem,
            remote.search,
            remote.recentProjects,
            remote.places,
            terminalInput,
            sessions ?: remote.sessions,
            remote.git,
            remote.connection,
            remote.network,
            remote.languageServers,
        )
    var created: AppState? = null
    setContent {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(services, scope, preferences, grammars, remoteFactory = JvmRemoteFactory) }
        created = state
        DisposableEffect(Unit) {
            onDispose {
                created?.dispose()
                client.close()
                proxy?.close()
                process.close()
            }
        }
        App(state)
    }
    waitForIdle()
    if (recent.isNotEmpty()) waitUntil(timeoutMillis = TIMEOUT) { created!!.recentProjects.size == recent.size }
    return E2eApp(created!!, services.recentProjects, process, client, proxy, terminalInput)
}

/** Opens the Open Project dialog from the welcome screen and waits for it to list a directory. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openProjectPickerFromWelcome() {
    onNodeWithTag(AppTags.WELCOME_OPEN).performClick()
    awaitTag(RemoteTags.OPEN_PROJECT)
}

/** Types [path] at the path field of the Open Project dialog and commits it with Enter (does not wait). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.navigateToInProjectPicker(path: String) {
    // A menu that opened the dialog can steal the focus back while closing; wait for the field to own it.
    val field = onNodeWithTag(RemoteTags.OPEN_PROJECT_PATH).requestFocus()
    awaitCondition { runCatching { field.assertIsFocused() }.isSuccess }
    field.performTextReplacement(path)
    field.performKeyInput { pressKey(Key.Enter) }
}

/** Navigates the Open Project dialog to [path] and waits until the dialog shows that directory. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.showDirectoryInProjectPicker(path: String) {
    navigateToInProjectPicker(path)
    // Lets the Enter start its navigation: until then the field already shows the typed path while the list, still
    // the previous one, is not loading, so the wait below would end at once on the previous directory.
    waitForIdle()
    awaitCondition {
        shownDirectoryInProjectPicker() == path &&
            onAllNodes(hasTestTag(RemoteTags.OPEN_PROJECT_LOADING)).fetchSemanticsNodes().isEmpty()
    }
}

/** Chooses [path] as the new project through the Open Project dialog, starting from the welcome screen. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openProjectDirectoryFromWelcome(path: String) {
    openProjectPickerFromWelcome()
    showDirectoryInProjectPicker(path)
    // A click on the disabled button is lost: wait for the listing to have enabled it.
    awaitCondition { runCatching { onNodeWithTag(RemoteTags.OPEN_PROJECT_OPEN).assertIsEnabled() }.isSuccess }
    onNodeWithTag(RemoteTags.OPEN_PROJECT_OPEN).performClick()
}

/** The directory the path field of the Open Project dialog shows. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.shownDirectoryInProjectPicker(): String? {
    val field =
        onAllNodes(hasTestTag(RemoteTags.OPEN_PROJECT_PATH)).fetchSemanticsNodes().singleOrNull()
            ?: return null
    return field.config
        .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.EditableText) { null }
        ?.text
}

/** Opens [project] through the Open Project dialog, as a user would, and waits for its tree. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.launchWithProject(
    project: SampleProject,
    preferences: PreferenceStore = InMemoryPreferenceStore(),
    chaos: Boolean = false,
    grammars: GrammarStore? = TestGrammars.shared,
    languageServers: LanguageServerSettings? = null,
): E2eApp {
    val app =
        launchApp(preferences = preferences, chaos = chaos, grammars = grammars, languageServers = languageServers)
    openProjectDirectoryFromWelcome(project.path)
    awaitTag(TreeTags.treeRow("src"))
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
        if (onAllNodes(hasTestTag(TreeTags.treeRow(child))).fetchSemanticsNodes().isEmpty()) {
            awaitTag(TreeTags.treeChevron(dir)).performClick()
            awaitTag(TreeTags.treeRow(child))
        }
    }
    onNodeWithTag(TreeTags.treeRow(relativePath)).performDoubleClick()
    awaitTag(EditorTags.tab(relativePath))
    awaitEditorReady()
}

/** Waits until the active tab finished loading (its content or a message is shown). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitEditorReady() {
    waitUntil(timeoutMillis = TIMEOUT) {
        onAllNodes(
            hasTestTag(EditorTags.EDITOR) or hasTestTag(EditorTags.EDITOR_MESSAGE),
        ).fetchSemanticsNodes().isNotEmpty()
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
        onAllNodes(hasTestTag(AppTags.STATUS_CONNECTION)).fetchSemanticsNodes().any { node ->
            node.config
                .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
                ?.any { "reconnecting" in it.text } == true
        }
    }
}

/** Waits until the status bar no longer warns about the server. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitReconnected() = awaitNoTag(AppTags.STATUS_CONNECTION)

/** Waits until the status bar's connection message contains [text]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitStatusContains(text: String, timeout: Long = TIMEOUT) {
    awaitCondition(timeout) {
        onAllNodes(hasTestTag(AppTags.STATUS_CONNECTION)).fetchSemanticsNodes().any { node ->
            node.config
                .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
                ?.any { text in it.text } == true
        }
    }
}

/** Opens [path] from the list of recent projects of the welcome screen, and waits for its tree. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openRecentFromWelcome(path: String) {
    awaitTag(AppTags.recentProject(path)).performClick()
    awaitTag(AppTags.PROJECT)
    awaitCondition { onAllNodes(hasTestTagPrefix("tree:")).fetchSemanticsNodes().isNotEmpty() }
}

/** Clicks the project name in the toolbar, then [path] among the recent projects of its menu. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.switchProjectTo(path: String) {
    onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
    awaitTag(AppTags.switchProject(path)).performClick()
    awaitNoTag(AppTags.PROJECT_MENU)
}
