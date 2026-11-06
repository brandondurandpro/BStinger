package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test

private const val CONFLICT_LINES = 20_000

/**
 * Performance of the conflict banner's Compare, through the real UI: a file of [CONFLICT_LINES] lines with unsaved
 * edits whose every line the disk rewrote shows the banner, and its Compare opens a diff of both contents. The
 * middle exceeds the table cap, so the diff builds once, as whole blocks, not line by line — and only on open.
 */
@OptIn(ExperimentalTestApi::class)
class DiskComparePerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the compare of a file rewritten throughout opens on a conflict`() =
        runComposeUiTest {
            val original = List(CONFLICT_LINES) { "val kept = $it" }
            val rewritten = List(CONFLICT_LINES) { "val written = $it" }
            project.file("big/Rewritten.kt", (original + "").joinToString("\n"))
            launchWithProject(project)
            openFromTree("big/Rewritten.kt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) }
            }
            typeCode("z")
            // Another program rewrites every line, in one burst; the banner asks what to keep.
            project.file("big/Rewritten.kt", (rewritten + "").joinToString("\n"))
            awaitTag(EditorTags.DISK_CONFLICT)
            measure(
                "open the compare of a ${CONFLICT_LINES}-line file rewritten throughout",
                budgetMillis = 10_000,
                budgetMegabytes = 80,
            ) {
                onNodeWithTag(EditorTags.DISK_COMPARE).performClick()
                awaitTag(EditorTags.DIFF)
                renderFrame()
            }
        }
}
