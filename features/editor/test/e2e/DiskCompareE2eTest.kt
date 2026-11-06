package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The Compare of the conflict banner: the editor's content against what another program wrote, shown as the diff
 * views of the Git panel show theirs, and closed back to the editor while the conflict waits for a choice.
 */
@OptIn(ExperimentalTestApi::class)
class DiskCompareE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the conflict banner offers Compare, and it shows the disk's line removed and the editor's added`() =
        runComposeUiTest {
            launchWithProject(project)
            conflictOnReadme()
            onNodeWithTag(EditorTags.DISK_COMPARE).performClick()
            // The compare opens in its own tab, as a diff view like the Git panel's.
            awaitTag(EditorTags.compareTab("README.md"))
            awaitTag(EditorTags.DIFF)
            // What the disk holds is the old side (its row is a removal), what the editor holds the new one.
            awaitDiffRow(0, kind = "Removed", text = "Written by another program")
            awaitDiffRow(1, kind = "Added", text = "z# Demo")
        }

    @Test
    fun `the compare labels its sides Disk and Editor`() =
        runComposeUiTest {
            launchWithProject(project)
            conflictOnReadme()
            onNodeWithTag(EditorTags.DISK_COMPARE).performClick()
            awaitTag(EditorTags.DIFF_SIDES)
            onNodeWithText("Disk").assertExists()
            onNodeWithText("Editor").assertExists()
        }

    @Test
    fun `closing the compare returns to the editor, and Load from Disk still resolves the conflict`() =
        runComposeUiTest {
            launchWithProject(project)
            conflictOnReadme()
            onNodeWithTag(EditorTags.DISK_COMPARE).performClick()
            awaitTag(EditorTags.DIFF)
            onNodeWithTag(EditorTags.compareTabClose("README.md")).performClick()
            awaitNoTag(EditorTags.compareTab("README.md"))
            awaitTag(EditorTags.tab("README.md"))
            // The conflict is still there, and the disk keeps to its word once asked again.
            awaitTag(EditorTags.DISK_CONFLICT)
            onNodeWithTag(EditorTags.DISK_RELOAD).performClick()
            awaitLine(0, "# Written by another program")
            awaitNoTag(EditorTags.DISK_CONFLICT)
        }

    @Test
    fun `Escape closes the compare back to the editor`() =
        runComposeUiTest {
            launchWithProject(project)
            conflictOnReadme()
            onNodeWithTag(EditorTags.DISK_COMPARE).performClick()
            awaitTag(EditorTags.DIFF)
            onNodeWithTag(EditorTags.DIFF).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.compareTab("README.md"))
            awaitTag(EditorTags.tab("README.md"))
            awaitTag(EditorTags.DISK_CONFLICT)
        }

    @Test
    fun `a compare recomputed later shows the contents of the moment the user asked`() =
        runComposeUiTest {
            launchWithProject(project)
            conflictOnReadme()
            // First compare: the editor's one edit against what the other program wrote.
            onNodeWithTag(EditorTags.DISK_COMPARE).performClick()
            awaitDiffRow(1, kind = "Added", text = "z# Demo")
            onNodeWithTag(EditorTags.DIFF).performKeyInput { pressKey(Key.Escape) }
            // The user edits a bit more while the conflict is open; the next Compare recomputes, not the open one.
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
            typeCode("y")
            // Wait for the second change to reach the watch too (the witness), so the compare surely sees it.
            project.file("Witness.kt", "class Witness\n")
            awaitTag(TreeTags.treeRow("Witness.kt"))
            onNodeWithTag(EditorTags.DISK_COMPARE).performClick()
            awaitTag(EditorTags.compareTab("README.md"))
            awaitDiffRow(1, kind = "Added", text = "yz# Demo")
        }

    // Opens README.md, types in it without saving, then another program rewrites it (the conflict of the banner).
    private fun ComposeUiTest.conflictOnReadme() {
        openFromTree("README.md")
        onNodeWithTag(EditorTags.EDITOR).performClick()
        onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
        typeCode("z")
        project.file("README.md", "# Written by another program\n")
        awaitTag(EditorTags.DISK_CONFLICT)
    }

    // Waits until the line [index] of the editor holds [text].
    private fun ComposeUiTest.awaitLine(index: Int, text: String) {
        awaitCondition {
            runCatching {
                onNodeWithTag(EditorTags.line(index)).assertTextContains(text, substring = true)
            }.isSuccess
        }
    }

    // Waits until row [index] of the open diff is of [kind] and shows [text].
    private fun ComposeUiTest.awaitDiffRow(index: Int, kind: String, text: String) {
        awaitCondition {
            runCatching {
                onNodeWithTag(EditorTags.diffLineKind(index, kind)).assertTextContains(text, substring = true)
            }.isSuccess
        }
    }
}
