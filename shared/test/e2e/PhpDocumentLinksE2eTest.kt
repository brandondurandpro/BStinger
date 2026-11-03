package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val DOCS_URL = "https://example.com/greeter"
private const val REQUIRED = "/Greeter.php"
private val APP_PHP =
    listOf("<?php", "// Docs: $DOCS_URL", "require __DIR__ . '$REQUIRED';", "\$greeter = new Greeter();", "")
        .joinToString("\n")
private const val URL_LINE = 1
private const val REQUIRE_LINE = 2
private const val NEW_LINE = 3

/** Document links in PHP (textDocument/documentLink): Ctrl+click opens a required file or a web address. */
@OptIn(ExperimentalTestApi::class)
class PhpDocumentLinksE2eTest {
    private val project = codeInsightProject().apply { file("src/app.php", APP_PHP) }
    private val created = ArrayList<FakePhpServers>()
    private val opened = ArrayList<String>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun E2eApp.activePath(): String? = project.editor.activeTab?.relativePath

    private fun column(line: Int, text: String): Int = APP_PHP.lines()[line].indexOf(text)

    private fun ComposeUiTest.open(vararg options: String): E2eApp {
        val servers = FakePhpServers(*options).chosen().also { created += it }
        val app = launchWithProject(project, languageServers = servers.settings)
        app.project.openUrl = { synchronized(opened) { opened += it } }
        openFromTree("src/app.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    // The links arrive with the usage counts.
    private fun ComposeUiTest.awaitLinks(app: E2eApp, count: Int) =
        awaitCondition(SERVER_START_MS) {
            val document =
                app.project.editor.activeTab
                    ?.document
            val insight = document?.let { app.project.insight.insightOf(it) }
            insight?.links?.size == count
        }

    @Test
    fun `Ctrl+click on a required file opens it`() =
        runComposeUiTest {
            val app = open()
            awaitLinks(app, 2)
            ctrlClickAt(REQUIRE_LINE, column(REQUIRE_LINE, REQUIRED) + 2)
            awaitCondition(SERVER_START_MS) { app.activePath() == "src/Greeter.php" }
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.caret
                    ?.line == 0
            }
        }

    @Test
    fun `Ctrl+click on a web address opens it in the browser, and stays in the file`() =
        runComposeUiTest {
            val app = open()
            awaitLinks(app, 2)
            ctrlClickAt(URL_LINE, column(URL_LINE, DOCS_URL) + 10)
            awaitCondition { synchronized(opened) { opened.toList() } == listOf(DOCS_URL) }
            assertEquals("src/app.php", app.activePath())
        }

    @Test
    fun `with Ctrl held, the whole link is underlined, not the word under the mouse`() =
        runComposeUiTest {
            val app = open()
            awaitLinks(app, 2)
            ctrlHoverAt(URL_LINE, column(URL_LINE, DOCS_URL) + 10)
            awaitTag(EditorTags.LINK, unmerged = true)
            assertEquals(DOCS_URL, textOf(EditorTags.LINK))
            // Ctrl released, then held again over the required file.
            onNodeWithTag(EditorTags.lineText(URL_LINE), useUnmergedTree = true).performKeyInput {
                keyUp(Key.CtrlLeft)
            }
            awaitNoTag(EditorTags.LINK, unmerged = true)
            ctrlHoverAt(REQUIRE_LINE, column(REQUIRE_LINE, REQUIRED) + 2)
            awaitCondition { textOf(EditorTags.LINK) == REQUIRED }
        }

    @Test
    fun `Ctrl+click elsewhere still goes to the declaration`() =
        runComposeUiTest {
            val app = open()
            awaitLinks(app, 2)
            ctrlClickAt(NEW_LINE, column(NEW_LINE, "Greeter") + 2)
            awaitCondition(SERVER_START_MS) { app.activePath() == "src/Greeter.php" }
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.caret
                    ?.line == 5
            }
            assertEquals(emptyList(), synchronized(opened) { opened.toList() })
        }

    @Test
    fun `without links from the server, Ctrl+click on an address opens nothing`() =
        runComposeUiTest {
            val app = open("--without", "documentLink")
            ctrlClickAt(URL_LINE, column(URL_LINE, DOCS_URL) + 10)
            awaitTag(EditorTags.NOTICE, timeout = SERVER_START_MS)
            assertEquals(emptyList(), synchronized(opened) { opened.toList() })
            assertEquals("src/app.php", app.activePath())
        }
}
