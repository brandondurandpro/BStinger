package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test

private val NOTES = listOf("one", "two", "three", "").joinToString("\n")

/** The gutter marks computed from what is typed, while the network goes down: they need no server. */
@OptIn(ExperimentalTestApi::class)
class GitGutterLiveChaosE2eTest {
    private val project = SampleProject.standard()

    private fun E2eApp.tab() = project.editor.activeTab

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `lines typed while the network is down are marked, and still are once it is back`() =
        runComposeUiTest {
            project.file("notes.txt", NOTES)
            project.commitAll()
            val app = launchWithProject(project, chaos = true)
            openFromTree("notes.txt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            awaitCondition { app.tab()?.followsHead == true }
            app.network.goDown()
            awaitReconnecting()
            caretToEnd()
            typeCode("four")
            awaitTag(EditorTags.changeMarker(3, "Added"), unmerged = true)
            app.network.comeBack()
            awaitReconnected()
            awaitTag(EditorTags.changeMarker(3, "Added"), unmerged = true)
        }

    @Test
    fun `lines typed in an untracked file while the network is down are marked, and still are once it is back`() =
        runComposeUiTest {
            project.commitAll()
            project.file("fresh.txt", NOTES)
            val app = launchWithProject(project, chaos = true)
            openFromTree("fresh.txt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            awaitCondition { app.tab()?.followsHead == true }
            app.network.goDown()
            awaitReconnecting()
            caretToEnd()
            typeCode("four")
            awaitTag(EditorTags.changeMarker(3, "Added"), unmerged = true)
            app.network.comeBack()
            awaitReconnected()
            awaitTag(EditorTags.changeMarker(3, "Added"), unmerged = true)
        }
}
