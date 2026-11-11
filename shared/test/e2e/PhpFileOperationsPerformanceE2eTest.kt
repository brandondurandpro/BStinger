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

private val WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

// What the rename allocates: 158 MB on every Linux runner, but 243 to 694 MB on the Windows one, growing with the
// time it takes there (the slower the disk, the more the UI and the server allocate while they wait). 600 MB, and
// 1,500 MB on Windows, still fail a change that copies the open file or the project per use.
private const val LINUX_BUDGET_MB = 600L
private const val WINDOWS_BUDGET_MB = 1_500L
private val BUDGET_MB = if (WINDOWS) WINDOWS_BUDGET_MB else LINUX_BUDGET_MB

// Rewriting 500 files: 0.9 to 1.9 s on the Linux runners and 2.2 s on a local Windows VM, but 4.5 to 26.9 s on the
// Windows runner, whose disk is that slow on some runs (8 runs of 2026-11-11, three of them over 18 s). 15 s, and
// 45 s on Windows, still fail a change that scans or rewrites files per use (2,000) instead of per file (500).
private const val LINUX_BUDGET_MS = 15_000L
private const val WINDOWS_BUDGET_MS = 45_000L
private val BUDGET_MS = if (WINDOWS) WINDOWS_BUDGET_MS else LINUX_BUDGET_MS

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
                budgetMillis = BUDGET_MS,
                budgetMegabytes = BUDGET_MB,
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
