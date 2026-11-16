package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TodoTags
import kotlin.test.AfterTest
import kotlin.test.Test

private val TODO_FILE = "src/main/kotlin/Todos.kt"

// The markers sit on the lines 4 and 6 (counted from 1) of the file.
private val TODO_FILE_TEXT =
    textOf(
        "package demo",
        "",
        "class Todos {",
        "    // TODO: fix me",
        "    fun crash() {}",
        "    // FIXME: crash",
        "}",
        "",
    )

/** The Todo tool window: the markers the server half scans the project for, listed by file and line. */
@OptIn(ExperimentalTestApi::class)
class TodoE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun writeMarkers() {
        project.file(TODO_FILE, TODO_FILE_TEXT)
    }

    @Test
    fun `the Todo window lists the markers of the project with their file and line`() = runComposeUiTest {
        writeMarkers()
        launchWithProject(project)
        onNodeWithTag(TodoTags.TOOL_TODO).performClick()
        awaitTag(TodoTags.PANEL)
        awaitTag(TodoTags.fileGroup(TODO_FILE))
        awaitTag(TodoTags.item(TODO_FILE, 4))
        awaitTag(TodoTags.item(TODO_FILE, 6))
    }

    @Test
    fun `clicking a marker opens its file in the editor`() = runComposeUiTest {
        writeMarkers()
        launchWithProject(project)
        onNodeWithTag(TodoTags.TOOL_TODO).performClick()
        awaitTag(TodoTags.item(TODO_FILE, 6)).performClick()
        awaitTag(EditorTags.tab(TODO_FILE))
        awaitEditorReady()
    }

    @Test
    fun `a project without markers says so`() = runComposeUiTest {
        launchWithProject(project)
        onNodeWithTag(TodoTags.TOOL_TODO).performClick()
        awaitTag(TodoTags.EMPTY)
    }
}
