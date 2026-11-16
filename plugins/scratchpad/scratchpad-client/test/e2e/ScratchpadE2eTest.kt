package org.example.kotlineditor.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.example.kotlineditor.state.scratchpadState
import org.example.kotlineditor.ui.ScratchpadTags

/** The Scratchpad tool window: the notes of the project, edited in the panel and written to `scratchpad.md`. */
@OptIn(ExperimentalTestApi::class)
class ScratchpadE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun notesOnDisk(): ByteArray = Files.readAllBytes(project.root.resolve("scratchpad.md"))

    @Test
    fun `a note is saved to scratchpad md and comes back when the window reopens`() = runComposeUiTest {
        val app = launchWithProject(project)
        onNodeWithTag(ScratchpadTags.TOOL).performClick()
        awaitTag(ScratchpadTags.PANEL)
        awaitTag(ScratchpadTags.FIELD)
        onNodeWithTag(ScratchpadTags.FIELD).performClick()
        onNodeWithTag(ScratchpadTags.FIELD).performTextInput("Remember the milk")
        awaitTag(ScratchpadTags.UNSAVED)
        awaitTag(ScratchpadTags.SAVE).performClick()
        awaitNoTag(ScratchpadTags.UNSAVED)
        awaitCondition { app.project.scratchpadState().saves >= 1 }
        assertEquals("Remember the milk\n", project.read("scratchpad.md"))

        // Saving twice the same note is idempotent: the file ends with the same bytes.
        val afterFirst = notesOnDisk()
        val stable = DiskSnapshot.take(project)
        awaitTag(ScratchpadTags.SAVE).performClick()
        awaitCondition { app.project.scratchpadState().saves >= 2 }
        assertEquals(afterFirst.toList(), notesOnDisk().toList())

        // Shown again, the notes come back from the file.
        awaitTag(ScratchpadTags.HIDE).performClick()
        awaitNoTag(ScratchpadTags.PANEL)
        onNodeWithTag(ScratchpadTags.TOOL).performClick()
        awaitTag(ScratchpadTags.PANEL)
        awaitTag(ScratchpadTags.FIELD)
        awaitCondition { fieldNotes() == "Remember the milk\n" }
        awaitNoTag(ScratchpadTags.UNSAVED)

        // The reopen only reads: everything but the notes file is as it was.
        stable.assertUnchanged(project, except = setOf("scratchpad.md"))
    }

    @Test
    fun `a project without notes starts with an empty field and creates the file only on save`() = runComposeUiTest {
        launchWithProject(project)
        onNodeWithTag(ScratchpadTags.TOOL).performClick()
        awaitTag(ScratchpadTags.PANEL)
        awaitTag(ScratchpadTags.FIELD)
        awaitCondition { fieldNotes().isEmpty() }
        assertFalse(Files.exists(project.root.resolve("scratchpad.md")))
    }

    // What the field holds, as the semantics of the editor give it.
    private fun ComposeUiTest.fieldNotes(): String {
        val node = onNodeWithTag(ScratchpadTags.FIELD).fetchSemanticsNode()
        return node.config[SemanticsProperties.EditableText].text
    }
}
