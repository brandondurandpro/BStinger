package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val OLD_CLASS_FILE = "src/Greeter.php"
private const val NEW_CLASS_FILE = "src/Welcomer.php"
private const val OPEN_FILE = "src/index.php"
private const val RENAMED_CALL = "\$greeter = new Welcomer();"
private const val RENAMED_CLASS = "class Welcomer implements Polite"

/**
 * Renaming a class file when the network or the server fails: the class is renamed once, with its file, or not at
 * all when the rename fails; nothing stays stuck.
 */
@OptIn(ExperimentalTestApi::class)
class PhpFileOperationsChaosE2eTest {
    private val project = codeInsightProject()
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

    private fun exists(path: String): Boolean =
        project.root
            .resolve(path)
            .toFile()
            .exists()

    private fun E2eApp.openLines(): List<String> =
        project.editor.allTabs
            .firstOrNull { it.relativePath == OPEN_FILE && !it.isDiff }
            ?.document
            ?.lines
            ?.toList()
            .orEmpty()

    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        openFromTree(OPEN_FILE)
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    // Opens the rename dialog of Greeter.php and types the new name, without confirming.
    private fun ComposeUiTest.typeNewName() {
        rightClickOn(TreeTags.treeRow(OLD_CLASS_FILE))
        awaitTag(TreeTags.MENU_RENAME).performClick()
        awaitTag(AppTags.FILE_DIALOG_INPUT).performTextClearance()
        onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performTextInput("Welcomer.php")
    }

    private fun confirm(test: ComposeUiTest) = test.onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()

    // The class is renamed once, in the open file and in the moved one.
    private fun ComposeUiTest.awaitRenamedOnce(app: E2eApp) {
        awaitTag(TreeTags.treeRow(NEW_CLASS_FILE), timeout = SERVER_START_MS)
        awaitNoTag(AppTags.FILE_DIALOG)
        awaitCondition(SERVER_START_MS) { RENAMED_CALL in app.openLines() }
        assertEquals(1, app.openLines().count { "Welcomer" in it })
        assertTrue(RENAMED_CLASS in disk(NEW_CLASS_FILE))
        assertFalse("Welcomerr" in disk(NEW_CLASS_FILE))
        assertFalse(exists(OLD_CLASS_FILE))
    }

    @Test
    fun `a rename whose answers were lost renames the class once`() =
        runComposeUiTest {
            val app = open()
            typeNewName()
            app.network.dropFromServer = true
            confirm(this)
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            awaitRenamedOnce(app)
        }

    @Test
    fun `a rename confirmed while the network is down waits for it, then renames the class once`() =
        runComposeUiTest {
            val app = open()
            typeNewName()
            app.network.goDown()
            awaitReconnecting()
            confirm(this)
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            assertTrue(exists(OLD_CLASS_FILE))
            assertTrue(app.openLines().none { "Welcomer" in it })
            app.network.comeBack()
            awaitReconnected()
            awaitRenamedOnce(app)
        }

    @Test
    fun `on a slow link the rename completes and the class is renamed once`() =
        runComposeUiTest {
            val app = open()
            app.network.latencyMs = 400
            typeNewName()
            confirm(this)
            awaitRenamedOnce(app)
            app.network.latencyMs = 0
        }

    @Test
    fun `after the server restarts, a rename asks its new language server`() =
        runComposeUiTest {
            val app = open()
            val before = awaitServer(app) { it.isRunning() }
            app.process.restart()
            awaitReconnected()
            awaitServer(app) { it.isRunning() && it.pid != before.pid }
            typeNewName()
            confirm(this)
            awaitRenamedOnce(app)
        }

    @Test
    fun `a file deleted by another program fails to rename and leaves the code as it was`() =
        runComposeUiTest {
            val app = open()
            typeNewName()
            project.root
                .resolve(OLD_CLASS_FILE)
                .toFile()
                .delete()
            confirm(this)
            awaitTag(AppTags.FILE_DIALOG_ERROR)
            assertFalse(exists(NEW_CLASS_FILE))
            assertTrue(app.openLines().none { "Welcomer" in it })
        }
}
