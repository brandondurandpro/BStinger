package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The hints of the empty editor (no file open): a list of actions with their shortcuts. */
@OptIn(ExperimentalTestApi::class)
class EmptyEditorE2eTest {
    private val project = SampleProject.standard()

    private val actions =
        listOf(
            "Go to File",
            "Recent Files",
            "Find in Files",
            "File Structure",
            "Go to Symbol",
            "Project View",
            "Git",
            "Terminal",
            "Open a file",
        )

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the hints are shown when no file is open`() =
        runComposeUiTest {
            launchWithProject(project)
            awaitTag(AppTags.EDITOR_EMPTY)
            actions.forEach { action ->
                onNodeWithTag(AppTags.editorHint(action)).fetchSemanticsNode()
                onNodeWithTag(AppTags.editorHintShortcut(action)).fetchSemanticsNode()
            }
            assertTrue(actions.isNotEmpty())
        }

    @Test
    fun `the hint rows are aligned on the left edge, not staggered`() =
        runComposeUiTest {
            launchWithProject(project)
            awaitTag(AppTags.EDITOR_EMPTY)
            val lefts =
                actions.map { action ->
                    onNodeWithTag(AppTags.editorHint(action)).fetchSemanticsNode().boundsInRoot.left
                }
            assertEquals(
                1,
                lefts.distinct().size,
                "the hint rows must share the same left edge, got $lefts",
            )
        }

    @Test
    fun `the shortcut column is aligned across the hint rows`() =
        runComposeUiTest {
            launchWithProject(project)
            awaitTag(AppTags.EDITOR_EMPTY)
            val lefts =
                actions.map { action ->
                    onNodeWithTag(AppTags.editorHintShortcut(action)).fetchSemanticsNode().boundsInRoot.left
                }
            assertEquals(
                1,
                lefts.distinct().size,
                "the shortcuts must share the same left edge, got $lefts",
            )
        }
}
