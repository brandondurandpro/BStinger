package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val NOTES = listOf("one", "two", "three", "").joinToString("\n")

/** The gutter marks follow what is typed: they appear and go away before the file is saved. */
@OptIn(ExperimentalTestApi::class)
class GitGutterLiveE2eTest {
    private val project = SampleProject.standard()

    private fun E2eApp.tab() = project.editor.activeTab

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.openNotes(): E2eApp {
        project.file("notes.txt", NOTES)
        project.commitAll()
        val app = launchWithProject(project)
        openFromTree("notes.txt")
        onNodeWithTag(EditorTags.EDITOR).performClick()
        awaitCondition { app.tab()?.followsHead == true }
        return app
    }

    @Test
    fun `a line typed in a tracked file is marked as added before any save`() =
        runComposeUiTest {
            val app = openNotes()
            caretToEnd()
            typeCode("four")
            awaitTag(EditorTags.changeMarker(3, "Added"), unmerged = true)
            assertEquals(true, app.tab()?.document?.isModified)
        }

    @Test
    fun `a line edited in a tracked file is marked as modified, and the mark goes when the edit is undone`() =
        runComposeUiTest {
            openNotes()
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
            typeCode("x")
            awaitTag(EditorTags.changeMarker(0, "Modified"), unmerged = true)
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
            awaitNoTag(EditorTags.changeMarker(0, "Modified"), unmerged = true)
        }

    @Test
    fun `typing without saving changes nothing on the disk or in the repository`() =
        runComposeUiTest {
            project.file("notes.txt", NOTES)
            project.commitAll()
            val before = DiskSnapshot.take(project)
            val app = launchWithProject(project)
            openFromTree("notes.txt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            awaitCondition { app.tab()?.followsHead == true }
            caretToEnd()
            typeCode("four")
            awaitTag(EditorTags.changeMarker(3, "Added"), unmerged = true)
            before.assertUnchanged(project)
        }
}
