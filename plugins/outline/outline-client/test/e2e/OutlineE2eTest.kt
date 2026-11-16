package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.OutlineTags
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The Outline window: the declarations of the active file, and a click that goes to one, through the real UI and
 * the real server.
 */
@OptIn(ExperimentalTestApi::class)
class OutlineE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the outline button shows the declarations of the active file and a click goes to one`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            awaitTag(OutlineTags.TOOL_BUTTON).performClick()
            awaitTag(OutlineTags.PANEL)
            awaitTag(OutlineTags.item("main", 6))
            awaitTag(OutlineTags.item("greeting", 7))
            onNodeWithTag(OutlineTags.item("main", 6)).performClick()
            awaitCondition { app.project.editor.activeTab?.document?.caret == TextPos(6, 4) }
            // The panel stays open: the user can follow another declaration right away.
            awaitTag(OutlineTags.PANEL)
            awaitTag(OutlineTags.item("main", 6))
        }

    @Test
    fun `the outline follows the active tab`() =
        runComposeUiTest {
            launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            awaitTag(OutlineTags.TOOL_BUTTON).performClick()
            awaitTag(OutlineTags.item("main", 6))
            openFromTree("src/main/kotlin/util/Strings.kt")
            awaitTag(OutlineTags.item("shout", 2))
            awaitNoTag(OutlineTags.item("main", 6))
            // The outline is that of the file again when its tab becomes active.
            awaitTag(EditorTags.tab("src/main/kotlin/App.kt")).performClick()
            awaitTag(OutlineTags.item("main", 6))
            awaitNoTag(OutlineTags.item("shout", 2))
        }

    @Test
    fun `a file whose language has no outline shows nothing, and does not stay loading`() =
        runComposeUiTest {
            launchWithProject(project)
            openFromTree("docs/guide.md")
            awaitTag(OutlineTags.TOOL_BUTTON).performClick()
            awaitTag(OutlineTags.PANEL)
            awaitTag(OutlineTags.EMPTY)
            awaitNoTag(OutlineTags.LOADING)
            awaitNoTag(OutlineTags.item("main", 6))
        }

    @Test
    fun `the outline of a file without declarations gives way to the one of the file opened next`() =
        runComposeUiTest {
            project.file("Empty.kt", textOf("package demo", ""))
            launchWithProject(project)
            openFromTree("Empty.kt")
            awaitTag(OutlineTags.TOOL_BUTTON).performClick()
            awaitTag(OutlineTags.EMPTY)
            awaitNoTag(OutlineTags.LOADING)
            // A file with declarations outlines it as soon as it becomes the active tab.
            openFromTree("src/main/kotlin/App.kt")
            awaitTag(OutlineTags.item("main", 6))
            awaitTag(OutlineTags.item("greeting", 7))
            awaitNoTag(OutlineTags.EMPTY)
        }
}
