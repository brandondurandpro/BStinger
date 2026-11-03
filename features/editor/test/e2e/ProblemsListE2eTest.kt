package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Far enough below the others that going to it scrolls the editor.
internal const val FILLER_LINES = 60
internal const val SECOND_ERROR_LINE = 5 + FILLER_LINES

/** Two unknown calls (errors) around a deprecated call (a warning), the second one far below. */
internal val BROKEN_PHP =
    (
        listOf("<?php", "", "function ok() {}", "missingOne();", "// @deprecated-call here") +
            (1..FILLER_LINES).map { "// filler $it" } +
            listOf("missingTwo();", "")
        ).joinToString("\n")

/** The list of the problems of a PHP file, opened from the widget at the top right of the editor. */
@OptIn(ExperimentalTestApi::class)
class ProblemsListE2eTest {
    private val project =
        codeInsightProject().apply {
            file("src/Broken.php", BROKEN_PHP)
        }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun E2eApp.caret(): TextPos? =
        project.editor.activeTab
            ?.document
            ?.caret

    private fun ComposeUiTest.open(path: String = "src/Broken.php"): E2eApp {
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree(path)
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    private fun ComposeUiTest.openList() {
        awaitCondition(SERVER_START_MS) { textOf(EditorTags.PROBLEMS_ERRORS) == "2" }
        onNodeWithTag(EditorTags.PROBLEMS).performClick()
        awaitTag(EditorTags.PROBLEMS_LIST)
    }

    @Test
    fun `a click on the widget lists the problems in the order of the file, with their place`() =
        runComposeUiTest {
            open()
            openList()
            onNodeWithTag(EditorTags.PROBLEMS_LIST_TITLE).assertTextContains("2 errors, 1 warning", substring = true)
            onNodeWithTag(
                EditorTags.problem(0),
            ).assertTextContains("Function \"missingOne\" not found", substring = true)
            onNodeWithTag(EditorTags.problem(0)).assertTextContains("4:1", substring = true)
            onNodeWithTag(EditorTags.problem(1)).assertTextContains("Call to a deprecated function", substring = true)
            onNodeWithTag(EditorTags.problem(1)).assertTextContains("5:4", substring = true)
            onNodeWithTag(EditorTags.problem(2)).assertTextContains("missingTwo", substring = true)
            onNodeWithTag(EditorTags.problem(2)).assertTextContains("${SECOND_ERROR_LINE + 1}:1", substring = true)
            awaitNoTag(EditorTags.problem(3))
            // A second click on the widget closes it.
            onNodeWithTag(EditorTags.PROBLEMS).performClick()
            awaitNoTag(EditorTags.PROBLEMS_LIST)
        }

    @Test
    fun `a click on a row goes to its problem, below the screen, and closes the list`() =
        runComposeUiTest {
            val app = open()
            openList()
            onNodeWithTag(EditorTags.problem(2)).performClick()
            awaitNoTag(EditorTags.PROBLEMS_LIST)
            awaitCondition { app.caret() == TextPos(SECOND_ERROR_LINE, 0) }
            awaitTag(EditorTags.line(SECOND_ERROR_LINE))
        }

    @Test
    fun `the arrow of a row goes to its problem`() =
        runComposeUiTest {
            val app = open()
            openList()
            onNodeWithTag(EditorTags.problemGo(1), useUnmergedTree = true).performClick()
            awaitNoTag(EditorTags.PROBLEMS_LIST)
            awaitCondition { app.caret() == TextPos(4, 3) }
        }

    @Test
    fun `the arrows choose a problem, Enter goes to it, Up from the first one wraps to the last`() =
        runComposeUiTest {
            val app = open()
            openList()
            onNodeWithTag(EditorTags.problem(0)).assertIsSelected()
            editorKeys { pressKey(Key.DirectionDown) }
            onNodeWithTag(EditorTags.problem(1)).assertIsSelected()
            editorKeys { pressKey(Key.DirectionUp) }
            editorKeys { pressKey(Key.DirectionUp) }
            onNodeWithTag(EditorTags.problem(2)).assertIsSelected()
            editorKeys { pressKey(Key.Enter) }
            awaitNoTag(EditorTags.PROBLEMS_LIST)
            awaitCondition { app.caret() == TextPos(SECOND_ERROR_LINE, 0) }
        }

    @Test
    fun `Escape or a click beside the list closes it, the caret stays where it was`() =
        runComposeUiTest {
            val app = open()
            clickAt(2, 3)
            val before = app.caret()
            openList()
            editorKeys { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.PROBLEMS_LIST)
            assertEquals(before, app.caret())
            openList()
            onNodeWithTag(EditorTags.PROBLEMS_SCRIM).performClick()
            awaitNoTag(EditorTags.PROBLEMS_LIST)
            assertEquals(before, app.caret())
        }

    @Test
    fun `a file without problems says so`() =
        runComposeUiTest {
            open("src/Greeter.php")
            onNodeWithTag(EditorTags.PROBLEMS).performClick()
            awaitTag(EditorTags.PROBLEMS_LIST_EMPTY)
            onNodeWithTag(EditorTags.PROBLEMS_LIST_TITLE).assertTextContains("0 errors, 0 warnings", substring = true)
        }

    @Test
    fun `a problem fixed while the list is open leaves it`() =
        runComposeUiTest {
            open()
            clickAt(3, 0)
            openList()
            awaitTag(EditorTags.problem(2))
            // The list keeps only the arrows, Enter and Escape: the line of missingOne() is deleted below it.
            editorKeys { withKeyDown(Key.ShiftLeft) { pressKey(Key.MoveEnd) } }
            editorKeys { pressKey(Key.Delete) }
            awaitNoTag(EditorTags.problem(2), timeout = SERVER_START_MS)
            onNodeWithTag(EditorTags.problem(0)).assertTextContains("deprecated", substring = true)
            onNodeWithTag(EditorTags.PROBLEMS_LIST_TITLE).assertTextContains("1 error, 1 warning", substring = true)
        }
}
