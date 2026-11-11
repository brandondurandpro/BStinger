package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.TextClipboard
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TreeCopyPathE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.copyFrom(rowTag: String, menuTag: String): String? {
        rightClickOn(rowTag)
        awaitTag(menuTag).performClick()
        awaitNoTag(TreeTags.TREE_MENU)
        return TextClipboard.get()
    }

    @Test
    fun `a file copies its absolute path, its relative path and its name`() =
        runComposeUiTest {
            launchWithProject(project)
            awaitTag(TreeTags.treeChevron("docs")).performClick()
            val row = TreeTags.treeRow("docs/guide.md")
            val absolute = project.root.resolve("docs/guide.md").toString()
            assertEquals(absolute, copyFrom(row, TreeTags.MENU_COPY_PATH))
            assertEquals("docs/guide.md", copyFrom(row, TreeTags.MENU_COPY_RELATIVE_PATH))
            assertEquals("guide.md", copyFrom(row, TreeTags.MENU_COPY_NAME))
        }

    @Test
    fun `a directory copies its relative path without a trailing separator`() =
        runComposeUiTest {
            launchWithProject(project)
            val row = TreeTags.treeRow("docs")
            assertEquals("docs", copyFrom(row, TreeTags.MENU_COPY_RELATIVE_PATH))
            assertEquals(project.root.resolve("docs").toString(), copyFrom(row, TreeTags.MENU_COPY_PATH))
        }

    @Test
    fun `the project root copies a dot as its relative path`() =
        runComposeUiTest {
            launchWithProject(project)
            val row = TreeTags.treeRow(project.root.fileName.toString())
            assertEquals(".", copyFrom(row, TreeTags.MENU_COPY_RELATIVE_PATH))
            assertEquals(project.root.toString(), copyFrom(row, TreeTags.MENU_COPY_PATH))
        }
}
