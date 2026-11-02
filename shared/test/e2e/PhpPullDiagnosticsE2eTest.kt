package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test

// A call of `helper`, which helpers.php does not declare yet.
private val HELPER_CALLS_PHP = listOf("<?php", "helper();", "").joinToString("\n")
private val EMPTY_HELPERS_PHP = listOf("<?php", "", "").joinToString("\n")
private const val CALL_LINE = 1

/** Diagnostics of a server that gives them when asked (`textDocument/diagnostic`) instead of publishing them. */
@OptIn(ExperimentalTestApi::class)
class PhpPullDiagnosticsE2eTest {
    private val project =
        codeInsightProject().apply {
            file("src/calls.php", HELPER_CALLS_PHP)
            file("src/helpers.php", EMPTY_HELPERS_PHP)
        }
    private val servers = FakePhpServers("--pull-diagnostics").chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree("src/calls.php")
        awaitServer(app) { it.isRunning() }
        return app
    }

    private fun error(line: Int) = EditorTags.diagnostic(line, "Error")

    @Test
    fun `the problems of a document are asked for, and again after each edit`() =
        runComposeUiTest {
            open()
            awaitTag(error(CALL_LINE), timeout = SERVER_START_MS, unmerged = true)
            caretToEnd()
            typeCode("other")
            typeCode("();")
            awaitTag(error(CALL_LINE + 1), timeout = SERVER_START_MS, unmerged = true)
            editorKeys { repeat("other();".length) { pressKey(Key.Backspace) } }
            awaitNoTag(error(CALL_LINE + 1), timeout = SERVER_START_MS, unmerged = true)
            awaitTag(error(CALL_LINE), unmerged = true)
        }

    @Test
    fun `a function declared and saved in another file clears the problem when the server asks to pull again`() =
        runComposeUiTest {
            open()
            awaitTag(error(CALL_LINE), timeout = SERVER_START_MS, unmerged = true)
            openFromTree("src/helpers.php")
            awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
            caretToEnd()
            typeCode("function helper")
            typeCode("() {}")
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
            openFromTree("src/calls.php")
            awaitNoTag(error(CALL_LINE), timeout = SERVER_START_MS, unmerged = true)
        }
}
