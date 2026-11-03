package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Formatting while typing in PHP (textDocument/onTypeFormatting): after `;` and `}` the server formats around. */
@OptIn(ExperimentalTestApi::class)
class PhpTypeFormattingE2eTest {
    private val project = codeInsightProject().apply { file("src/format.php", FORMAT_PHP) }
    private val created = ArrayList<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun E2eApp.lines(): List<String> =
        project.editor.activeTab
            ?.document
            ?.lines
            ?.toList()
            .orEmpty()

    // Opened, with the trigger characters of the server known; the caret at the end of the statement.
    private fun ComposeUiTest.open(vararg options: String): E2eApp {
        val servers = FakePhpServers(*options).chosen().also { created += it }
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree("src/format.php")
        awaitServer(app) { it.isRunning() }
        awaitCondition(SERVER_START_MS) {
            val document =
                app.project.editor.activeTab
                    ?.document
            document?.let { app.project.insight.insightOf(it) }?.typeFormattingTriggers != null
        }
        clickAt(STATEMENT_LINE, 4)
        editorKeys { pressKey(Key.MoveEnd) }
        return app
    }

    @Test
    fun `a semicolon typed after spaces pulls them in`() =
        runComposeUiTest {
            val app = open()
            typeCode(" ;")
            awaitCondition(SERVER_START_MS) { app.lines().getOrNull(STATEMENT_LINE) == FORMATTED_STATEMENT }
            assertEquals(
                FORMATTED_STATEMENT.length,
                app.project.editor.activeTab
                    ?.document
                    ?.caret
                    ?.column,
            )
        }

    @Test
    fun `a closing brace typed on its own line takes the indentation of its opening line`() =
        runComposeUiTest {
            val app = open()
            typeCode(";\n")
            // The editor indents the new line like the statement; the server moves the brace back.
            typeCode("}")
            awaitCondition(SERVER_START_MS) { app.lines().getOrNull(STATEMENT_LINE + 1) == "}" }
            assertEquals(FORMATTED_STATEMENT, app.lines().getOrNull(STATEMENT_LINE))
        }

    @Test
    fun `undoing the formatting does not format again`() =
        runComposeUiTest {
            val app = open()
            typeCode(" ;")
            awaitCondition(SERVER_START_MS) { app.lines().getOrNull(STATEMENT_LINE) == FORMATTED_STATEMENT }
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
            awaitCondition { app.lines().getOrNull(STATEMENT_LINE) == "    \$a = 1 ;" }
            mainClock.advanceTimeBy(1_500)
            waitForIdle()
            assertEquals("    \$a = 1 ;", app.lines().getOrNull(STATEMENT_LINE))
        }

    @Test
    fun `other characters are not formatted, nor anything without the feature on the server`() =
        runComposeUiTest {
            val app = open("--without", "onTypeFormatting")
            typeCode(" ;")
            awaitCondition { app.lines().getOrNull(STATEMENT_LINE) == "    \$a = 1 ;" }
            mainClock.advanceTimeBy(1_500)
            waitForIdle()
            assertEquals("    \$a = 1 ;", app.lines().getOrNull(STATEMENT_LINE))
        }
}
