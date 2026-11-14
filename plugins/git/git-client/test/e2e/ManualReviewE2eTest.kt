package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.ReviewState
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.GitTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** "Review manually" in the Git panel opens a tab: the changed files with an OK checkbox, and the diff of one. */
@OptIn(ExperimentalTestApi::class)
class ManualReviewE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun changeTwoFiles() {
        project.commitAll()
        project.file("README.md", "# Changed\n")
        project.file("build.gradle.kts", "plugins {}\n")
    }

    private fun ComposeUiTest.openReview(app: E2eApp): ReviewState {
        app.project.toolWindows.setVisible(ToolWindow.Git, true)
        awaitTag(GitTags.GIT_REVIEW_MANUAL).performClick()
        awaitTag(EditorTags.REVIEW)
        val activeTab = app.project.editor.activeTab
        val status = activeTab?.status
        val review = status as? TabStatus.Review
        return assertNotNull(review).state
    }

    @Test
    fun `the button opens a review tab that lists the changed files and shows the first diff`() =
        runComposeUiTest {
            changeTwoFiles()
            val app = launchWithProject(project)
            val state = openReview(app)

            awaitTag(EditorTags.REVIEW_TAB)
            awaitTag(EditorTags.reviewFile("README.md"))
            awaitTag(EditorTags.reviewFile("build.gradle.kts"))
            assertEquals(listOf("README.md", "build.gradle.kts"), state.files.map { it.relativePath })
            assertEquals("README.md", state.selected)
            awaitTag(EditorTags.DIFF)
        }

    @Test
    fun `a click on a file shows its diff on the right`() =
        runComposeUiTest {
            changeTwoFiles()
            val app = launchWithProject(project)
            val state = openReview(app)

            awaitTag(EditorTags.reviewFile("README.md")).performClick()
            awaitCondition { state.selected == "README.md" }
            awaitTag(EditorTags.DIFF)
            awaitTag(EditorTags.reviewFile("build.gradle.kts")).performClick()
            awaitCondition { state.selected == "build.gradle.kts" }
            awaitTag(EditorTags.DIFF)
        }

    @Test
    fun `the checkbox says OK or not OK and the count follows`() =
        runComposeUiTest {
            changeTwoFiles()
            val app = launchWithProject(project)
            val state = openReview(app)
            assertEquals(0, state.approvedCount)

            awaitTag(EditorTags.reviewCheck("README.md")).performClick()
            awaitCondition { state.isApproved("README.md") }
            assertEquals(1, state.approvedCount)
            assertFalse(state.isApproved("build.gradle.kts"))

            awaitTag(EditorTags.reviewCheck("README.md")).performClick()
            awaitCondition { !state.isApproved("README.md") }
            assertEquals(0, state.approvedCount)
        }

    @Test
    fun `opening the review again reuses its tab and keeps the verdicts`() =
        runComposeUiTest {
            changeTwoFiles()
            val app = launchWithProject(project)
            val state = openReview(app)
            awaitTag(EditorTags.reviewCheck("README.md")).performClick()
            awaitCondition { state.isApproved("README.md") }

            awaitTag(GitTags.GIT_REVIEW_MANUAL).performClick()
            val allTabs = app.project.editor.allTabs
            val tabs = allTabs.filter { it.isReview }
            assertEquals(1, tabs.size)
            assertTrue(state.isApproved("README.md"))
        }

    @Test
    fun `reviewing changes nothing on disk`() =
        runComposeUiTest {
            changeTwoFiles()
            val before = DiskSnapshot.take(project)
            val app = launchWithProject(project)
            val state = openReview(app)
            awaitTag(EditorTags.reviewCheck("README.md")).performClick()
            awaitCondition { state.isApproved("README.md") }
            awaitTag(EditorTags.reviewFile("README.md")).performClick()
            awaitTag(EditorTags.DIFF)

            before.assertUnchanged(project)
        }
}
