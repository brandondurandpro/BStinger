package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val LINKS = 3_000
private const val URL_COLUMN = 3

/** Document links of a PHP file of 3,000 web addresses: arriving, following typing, underlined at the end. */
@OptIn(ExperimentalTestApi::class)
class PhpDocumentLinksPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            val comments = (1..LINKS).map { "// https://example.com/page/$it" }
            file("src/links.php", (listOf("<?php") + comments + "").joinToString("\n"))
        }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `3,000 links arrive, follow typing and are underlined at the end of the file`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            val opened = ArrayList<String>()
            app.project.openUrl = { synchronized(opened) { opened += it } }
            openFromTree("src/links.php")
            awaitServer(app) { it.isRunning() }
            val document =
                app.project.editor.activeTab
                    ?.document
            val insight = document?.let { app.project.insight.insightOf(it) }
            measure("$LINKS document links from the server", budgetMillis = 15_000) {
                awaitCondition(SERVER_START_MS) { insight?.links?.size == LINKS }
                renderFrame()
            }
            clickAt(0, 0)
            measure("a line typed above $LINKS links", budgetMillis = 3_000) {
                editorKeys { pressKey(Key.MoveEnd) }
                editorKeys { pressKey(Key.Enter) }
                awaitCondition {
                    insight
                        ?.links
                        ?.firstOrNull()
                        ?.range
                        ?.startLine == 2
                }
                renderFrame()
            }
            val last = LINKS + 1
            measure("Ctrl over the last of $LINKS links", budgetMillis = 3_000) {
                editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
                ctrlHoverAt(last, URL_COLUMN + 10)
                awaitTag(EditorTags.LINK, unmerged = true)
                renderFrame()
            }
            assertEquals("https://example.com/page/$LINKS", textOf(EditorTags.LINK))
        }
}
