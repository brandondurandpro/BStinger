package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val DIRECTORIES = 2_000
private const val FILES = 5_000
private const val MAX_COMPOSED_ROWS = 100

/**
 * Performance of following the disk, through the real UI and server: watching a project of [DIRECTORIES]
 * directories, then bursts of [FILES] files created by another program, in the tree or in a build's output.
 */
@OptIn(ExperimentalTestApi::class)
class DiskChangesPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun name(i: Int) = "huge/file${i.toString().padStart(5, '0')}.txt"

    @Test
    fun `a project of two thousand directories is watched, and a change deep inside shows`() =
        runComposeUiTest {
            repeat(DIRECTORIES) { project.file("pkg/d${it.toString().padStart(4, '0')}/File.kt", "class File\n") }
            // The project opens once every directory is watched.
            measure("open and watch $DIRECTORIES directories", budgetMillis = 15_000) {
                launchWithProject(project)
            }
            onNodeWithTag(TreeTags.treeChevron("pkg")).performClick()
            awaitTag(TreeTags.treeChevron("pkg/d0000")).performClick()
            awaitTag(TreeTags.treeRow("pkg/d0000/File.kt"))
            measure("a file created deep inside shows", budgetMillis = 3_000) {
                project.file("pkg/d0000/New.kt", "class New\n")
                awaitTag(TreeTags.treeRow("pkg/d0000/New.kt"))
                renderFrame()
            }
        }

    @Test
    fun `five thousand files created at once by another program`() =
        runComposeUiTest {
            project.file(name(0), "")
            val app = launchWithProject(project)
            val tree = app.project.tree
            onNodeWithTag(TreeTags.treeChevron("huge")).performClick()
            awaitTag(TreeTags.treeRow(name(0)))
            measure("$FILES files created show in the tree", budgetMillis = 10_000) {
                for (i in 1 until FILES) project.file(name(i), "")
                awaitCondition(timeout = 10_000) { tree.rows.size > FILES }
                renderFrame()
            }
            val composed = onAllNodes(hasTestTagPrefix("tree:")).fetchSemanticsNodes().size
            assertTrue(composed < MAX_COMPOSED_ROWS, "composed rows: $composed")
        }

    @Test
    fun `a build writing thousands of files does not hold up the tree`() =
        runComposeUiTest {
            project.dir("build")
            launchWithProject(project)
            onNodeWithTag(TreeTags.treeChevron("src")).performClick()
            awaitTag(TreeTags.treeRow("src/main"))
            measure("$FILES build outputs, then a source shows", budgetMillis = 5_000) {
                repeat(FILES) { project.file("build/classes/c${it % 50}/C$it.class", "") }
                project.file("src/Witness.kt", "class Witness\n")
                awaitTag(TreeTags.treeRow("src/Witness.kt"))
                renderFrame()
            }
        }

    @Test
    fun `a build writing thousands of files around one open file reconciles fast`() =
        runComposeUiTest {
            project.file(name(0), "")
            val app = launchWithProject(project)
            openFromTree(name(0))
            val document =
                checkNotNull(
                    app.project.editor.allTabs
                        .firstOrNull { !it.isDiff }
                        ?.document,
                )
            // A build writes its thousands of outputs, and one drew in the same burst the open file: the watch
            // answers the burst with the version of the open file alone, the tab follows it, nothing else read.
            measure(
                "$FILES build outputs with one open file reconciled",
                budgetMillis = 15_000,
                budgetMegabytes = 1_000,
            ) {
                repeat(FILES) { project.file("build/classes/c${it % 50}/C$it.class", "") }
                project.file(name(0), "class Reconciled from a burst\n")
                awaitCondition(timeout = 30_000) {
                    // Reloaded: the document holds the version the burst left on the disk, its first line is it.
                    document.diskVersion?.sha256 == project.sha256Of(name(0)) &&
                        document.lines.firstOrNull() == "class Reconciled from a burst"
                }
                renderFrame()
            }
            awaitNoTag(EditorTags.DISK_CONFLICT)
        }
}
