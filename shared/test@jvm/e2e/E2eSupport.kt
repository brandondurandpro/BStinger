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
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.rpc.appServices
import org.example.kotlineditor.server.EditorServer
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.Screen
import org.example.kotlineditor.ui.Tags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

const val TIMEOUT = 10_000L

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

class E2eApp(
    val state: AppState,
    val recentStore: org.example.kotlineditor.model.RecentProjectsStore,
    val picker: FakePicker,
    val server: EditorServer,
    val client: RpcClient,
) {
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
): E2eApp {
    val data = Files.createTempDirectory("kotlin-editor-server").toFile()
    val server = EditorServer(LocalServices(data)).start()
    val client = RpcClient("127.0.0.1", server.port, server.token)
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
            remote.shell,
            sessions ?: remote.sessions,
            remote.git,
            remote.connection,
        )
    var created: AppState? = null
    setContent {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(services, scope) }
        created = state
        DisposableEffect(Unit) {
            onDispose {
                client.close()
                server.close()
            }
        }
        App(state)
    }
    waitForIdle()
    if (recent.isNotEmpty()) waitUntil(timeoutMillis = TIMEOUT) { created!!.recentProjects.size == recent.size }
    return E2eApp(created!!, services.recentProjects, services.directoryPicker as FakePicker, server, client)
}

/** Opens [project] through the welcome screen, as a user would, and waits for its tree. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.launchWithProject(project: SampleProject): E2eApp {
    val app = launchApp(pick = project.path)
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
