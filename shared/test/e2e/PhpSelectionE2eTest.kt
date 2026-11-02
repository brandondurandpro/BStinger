package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test

// In src/Greeter.php: `return "Hello " . strtoupper($name);` is line 15, `$name` columns 37 to 42.
private const val RETURN_LINE = 15
private const val NAME_COLUMN = 38

/** The selection as (start, end), null when there is none. */
internal fun E2eApp.selection(): Pair<TextPos, TextPos>? =
    project.editor.activeTab
        ?.document
        ?.selectionRange()

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.extendSelection() = editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.W) } }

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.shrinkSelection() =
    editorKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.W) } } }

internal fun span(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) =
    TextPos(startLine, startColumn) to TextPos(endLine, endColumn)

/** The variable, the statement, the method from its modifiers, the class, then the file, as phpactor gives them. */
internal val NAME_STEPS =
    listOf(
        span(RETURN_LINE, 37, RETURN_LINE, 42),
        span(RETURN_LINE, 8, RETURN_LINE, 44),
        span(13, 4, 16, 5),
        span(5, 0, 17, 1),
        span(0, 0, 18, 0),
    )

/** Ctrl+W and Ctrl+Shift+W in PHP, through the ranges of the language server (textDocument/selectionRange). */
@OptIn(ExperimentalTestApi::class)
class PhpSelectionE2eTest {
    private val project = codeInsightProject()
    private val created = ArrayList<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun ComposeUiTest.open(vararg options: String): E2eApp {
        val servers = FakePhpServers(*options).chosen().also { created += it }
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree("src/Greeter.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    @Test
    fun `Ctrl+W grows from the variable to the statement, the method, the class and the file`() =
        runComposeUiTest {
            val app = open()
            clickAt(RETURN_LINE, NAME_COLUMN)
            NAME_STEPS.forEach { step ->
                extendSelection()
                awaitCondition(SERVER_START_MS) { app.selection() == step }
            }
        }

    @Test
    fun `Ctrl+Shift+W walks back through the same ranges down to the caret`() =
        runComposeUiTest {
            val app = open()
            clickAt(RETURN_LINE, NAME_COLUMN)
            NAME_STEPS.forEach { step ->
                extendSelection()
                awaitCondition(SERVER_START_MS) { app.selection() == step }
            }
            NAME_STEPS.reversed().drop(1).forEach { step ->
                shrinkSelection()
                awaitCondition { app.selection() == step }
            }
            shrinkSelection()
            awaitCondition { app.selection() == null }
        }

    @Test
    fun `Ctrl+W at the end of the file keeps the whole file selected`() =
        runComposeUiTest {
            val app = open()
            clickAt(RETURN_LINE, NAME_COLUMN)
            NAME_STEPS.forEach { step ->
                extendSelection()
                awaitCondition(SERVER_START_MS) { app.selection() == step }
            }
            extendSelection()
            waitForIdle()
            awaitCondition { app.selection() == NAME_STEPS.last() }
        }

    @Test
    fun `a server without selection ranges leaves Ctrl+W to the editor, which selects the word without its dollar`() =
        runComposeUiTest {
            val app = open("--without", "selectionRange")
            clickAt(RETURN_LINE, NAME_COLUMN)
            extendSelection()
            awaitCondition(SERVER_START_MS) { app.selection() == span(RETURN_LINE, 38, RETURN_LINE, 42) }
            extendSelection()
            awaitCondition { app.selection() == span(RETURN_LINE, 8, RETURN_LINE, 44) }
        }

    @Test
    fun `after the caret moves elsewhere, Ctrl+W grows from the new place`() =
        runComposeUiTest {
            val app = open()
            clickAt(RETURN_LINE, NAME_COLUMN)
            extendSelection()
            awaitCondition(SERVER_START_MS) { app.selection() == NAME_STEPS.first() }
            // On `greet` of the method's declaration.
            clickAt(13, 22)
            extendSelection()
            awaitCondition(SERVER_START_MS) { app.selection() == span(13, 20, 13, 25) }
            extendSelection()
            awaitCondition { app.selection() == span(13, 4, 13, 59) }
        }

    @Test
    fun `after an edit, Ctrl+W follows the new text`() =
        runComposeUiTest {
            val app = open()
            clickAt(RETURN_LINE, NAME_COLUMN)
            extendSelection()
            awaitCondition(SERVER_START_MS) { app.selection() == NAME_STEPS.first() }
            // The selected `$name` becomes `who`: the statement is 2 columns shorter.
            editorKeys { pressKey(Key.Delete) }
            typeInEditor("who")
            editorKeys { pressKey(Key.Escape) }
            extendSelection()
            awaitCondition(SERVER_START_MS) { app.selection() == span(RETURN_LINE, 37, RETURN_LINE, 40) }
            extendSelection()
            awaitCondition { app.selection() == span(RETURN_LINE, 8, RETURN_LINE, 42) }
        }
}
