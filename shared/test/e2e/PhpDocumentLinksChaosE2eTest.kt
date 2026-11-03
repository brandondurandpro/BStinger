package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val WIKI_URL = "https://example.com/wiki"
private val LINKS_PHP = listOf("<?php", "// See $WIKI_URL", "\$greeter = new Greeter();", "").joinToString("\n")
private const val WIKI_LINE = 1

/** Document links when the network or the server fails: they follow edits, and are asked again after. */
@OptIn(ExperimentalTestApi::class)
class PhpDocumentLinksChaosE2eTest {
    private val project = codeInsightProject().apply { file("src/links.php", LINKS_PHP) }
    private val servers = FakePhpServers().chosen()
    private val opened = ArrayList<String>()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun urlColumn(): Int = LINKS_PHP.lines()[WIKI_LINE].indexOf(WIKI_URL) + 10

    private fun openedUrls(): List<String> = synchronized(opened) { opened.toList() }

    private fun E2eApp.links() =
        project.editor.activeTab
            ?.document
            ?.let { project.insight.insightOf(it) }
            ?.links
            .orEmpty()

    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        app.project.openUrl = { synchronized(opened) { opened += it } }
        openFromTree("src/links.php")
        awaitServer(app) { it.isRunning() }
        awaitCondition(SERVER_START_MS) { app.links().size == 1 }
        return app
    }

    @Test
    fun `a line typed while the link is silent moves the link, Ctrl+click follows it at once`() =
        runComposeUiTest {
            val app = open()
            app.network.blackHole = true
            clickAt(0, 0)
            editorKeys { pressKey(Key.MoveEnd) }
            editorKeys { pressKey(Key.Enter) }
            awaitCondition {
                app
                    .links()
                    .singleOrNull()
                    ?.range
                    ?.startLine == WIKI_LINE + 1
            }
            // Known already: the browser opens without the server.
            ctrlClickAt(WIKI_LINE + 1, urlColumn())
            awaitCondition { openedUrls() == listOf(WIKI_URL) }
            awaitReconnecting()
            app.network.blackHole = false
            awaitReconnected()
            assertEquals(
                WIKI_LINE + 1,
                app
                    .links()
                    .singleOrNull()
                    ?.range
                    ?.startLine,
            )
        }

    @Test
    fun `after the server restarts, the links are asked again and still followed`() =
        runComposeUiTest {
            val app = open()
            app.process.restart()
            awaitReconnected()
            awaitServer(app) { it.isRunning() }
            // The address lengthened after the restart: only the new server knows it whole.
            val end = LINKS_PHP.lines()[WIKI_LINE].length
            clickAt(WIKI_LINE, end - 1)
            editorKeys { pressKey(Key.MoveEnd) }
            typeInEditor("x")
            awaitCondition(SERVER_START_MS) {
                app
                    .links()
                    .singleOrNull()
                    ?.range
                    ?.endColumn == end + 1
            }
            ctrlClickAt(WIKI_LINE, urlColumn())
            awaitCondition { openedUrls() == listOf("${WIKI_URL}x") }
        }

    @Test
    fun `on a slow link, Ctrl+click on a known link opens it at once`() =
        runComposeUiTest {
            val app = open()
            app.network.latencyMs = 1_500
            ctrlClickAt(WIKI_LINE, urlColumn())
            awaitCondition(timeout = 1_000) { openedUrls() == listOf(WIKI_URL) }
            assertEquals(
                "src/links.php",
                app.project.editor.activeTab
                    ?.relativePath,
            )
            awaitNoTag(EditorTags.NOTICE)
        }
}
