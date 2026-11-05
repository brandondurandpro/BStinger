package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Files using the class, closed: each rewritten on disk.
private const val USING_FILES = 500

// Uses of the class in the open file: each edited in the editor.
private const val OPEN_USES = 2_000

/** Renaming a class file whose class is used in 500 closed files and 2,000 times in the open one. */
@OptIn(ExperimentalTestApi::class)
class PhpFileOperationsPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            file("src/Widget.php", listOf("<?php", "class Widget", "{", "}", "").joinToString("\n"))
            (1..USING_FILES).forEach { file("src/uses/use$it.php", "<?php\n\$w$it = new Widget();\n") }
            val uses = (1..OPEN_USES).map { "\$w$it = new Widget();" }
            file("src/main.php", (listOf("<?php") + uses + "").joinToString("\n"))
        }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun disk(path: String): String =
        project.root
            .resolve(path)
            .toFile()
            .readText()

    @Test
    fun `a class used in 500 files and 2,000 times in the open one is renamed with its file`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/main.php")
            awaitServer(app) { it.isRunning() }
            awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
            val document =
                app.project.editor.activeTab
                    ?.document
            rightClickOn(TreeTags.treeRow("src/Widget.php"))
            awaitTag(TreeTags.MENU_RENAME).performClick()
            awaitTag(AppTags.FILE_DIALOG_INPUT).performTextClearance()
            onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performTextInput("Gadget.php")
            measure(
                "rename of a class used in $USING_FILES files and $OPEN_USES times",
                // Rewriting 500 files: 1.5 to 1.9 s on the Linux runners, but 0.9 to 3.1 s on the Windows one, and
                // 9.2 s when its disk was slow (run 42096833220). 15 s still fails a change that scans or rewrites
                // files per use instead of per file.
                budgetMillis = 15_000,
                budgetMegabytes = 600,
            ) {
                onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
                awaitTag(TreeTags.treeRow("src/Gadget.php"), timeout = SERVER_START_MS)
                awaitNoTag(AppTags.FILE_DIALOG, timeout = SERVER_START_MS)
                renderFrame()
            }
            val lines = document?.lines?.toList().orEmpty()
            assertEquals(OPEN_USES, lines.count { "new Gadget();" in it })
            assertTrue("class Gadget" in disk("src/Gadget.php"))
            assertEquals("<?php\n\$w$USING_FILES = new Gadget();\n", disk("src/uses/use$USING_FILES.php"))
            assertTrue((1..USING_FILES).all { "Gadget" in disk("src/uses/use$it.php") })
        }
}
