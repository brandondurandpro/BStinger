package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test

/** Project-wide searches, which run on the server, while the network fails. */
@OptIn(ExperimentalTestApi::class)
class SearchChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.openFindInFiles() {
        onNodeWithTag(Tags.TREE).performClick()
        onNodeWithTag(Tags.TREE).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
        }
        awaitTag(Tags.FIND_IN_FILES_INPUT)
    }

    @Test
    fun `Find in Files typed while the network is down lists the matches once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFindInFiles()
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("Hello")
            app.network.comeBack()
            awaitTag(ItemTags.findInFilesItem("docs/guide.md", 1))
            awaitTag(ItemTags.findInFilesItem("src/main/kotlin/App.kt", 7))
            onNodeWithTag(Tags.FIND_IN_FILES_COUNT).assertTextContains("2 matches in 2 files")
        }

    @Test
    fun `Find in Files on a slow network lists the matches of the last query typed`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            app.network.latencyMs = 300
            openFindInFiles()
            // Each key starts a search; the answers of the earlier queries arrive late and must not win.
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("Hel")
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("lo from")
            awaitTag(ItemTags.findInFilesItem("docs/guide.md", 1))
            awaitCondition {
                runCatching {
                    onNodeWithTag(Tags.FIND_IN_FILES_COUNT).assertTextContains("1 matches in 1 files")
                }.isSuccess
            }
            awaitNoTag(ItemTags.findInFilesItem("src/main/kotlin/App.kt", 7))
        }
}
