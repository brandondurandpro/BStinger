package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SplitEditorE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.inPane(index: Int, tag: String) =
        onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(Tags.pane(index))))

    private fun ComposeUiTest.rightClickTab(path: String, pane: Int) {
        inPane(pane, Tags.tab(path)).performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
    }

    private fun ComposeUiTest.splitRight(path: String) {
        rightClickTab(path, 0)
        awaitTag(Tags.MENU_SPLIT_RIGHT).performClick()
        awaitTag(Tags.pane(1))
        awaitCondition { onAllNodesWithTag(Tags.EDITOR).fetchSemanticsNodes().size == 2 }
    }

    private fun E2eApp.tabsOf(pane: Int) = project.editor.panes[pane].tabs

    private fun E2eApp.names(pane: Int): List<String> = tabsOf(pane).map { tab -> tab.entry.name }

    private fun E2eApp.firstLine(pane: Int): String {
        val document = tabsOf(pane).single().document
        return document!!.lines[0]
    }

    private fun E2eApp.activeNameOf(pane: Int): String? {
        val tab = project.editor.panes[pane].activeTab
        return tab?.entry?.name
    }

    private fun SemanticsNodeInteraction.assertLine(text: String) = assertTextContains(text, substring = true)

    @Test
    fun `Split Right shows two panes side by side with their own tab strip`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            openFromTree("docs/guide.md")
            splitRight("docs/guide.md")
            assertEquals(2, app.project.editor.panes.size)
            inPane(0, Tags.tab("README.md")).assertIsDisplayed()
            inPane(0, Tags.tab("docs/guide.md")).assertIsDisplayed()
            inPane(1, Tags.tab("docs/guide.md")).assertIsDisplayed()
            val readmeInRight = hasTestTag(Tags.tab("README.md")) and hasAnyAncestor(hasTestTag(Tags.pane(1)))
            assertTrue(onAllNodes(readmeInRight).fetchSemanticsNodes().isEmpty())
            val left = inPane(0, Tags.EDITOR).fetchSemanticsNode().boundsInRoot
            val right = inPane(1, Tags.EDITOR).fetchSemanticsNode().boundsInRoot
            assertTrue(left.right <= right.left, "panes are side by side: $left / $right")
        }

    @Test
    fun `editing a file in one pane updates the other pane`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            splitRight("README.md")
            val editor = app.project.editor
            assertSame(app.tabsOf(0).single().document, app.tabsOf(1).single().document)
            inPane(1, Tags.EDITOR).performClick()
            inPane(1, Tags.EDITOR).performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) }
                pressKey(Key.X)
            }
            waitForIdle()
            awaitCondition { app.firstLine(0).startsWith("x") }
            inPane(0, Tags.line(0)).assertLine("x# Demo")
            inPane(1, Tags.line(0)).assertLine("x# Demo")
        }

    @Test
    fun `clicking a pane makes it the active one`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            openFromTree("docs/guide.md")
            splitRight("docs/guide.md")
            val editor = app.project.editor
            assertSame(editor.panes[1], editor.activePane)
            inPane(0, Tags.EDITOR).performClick()
            awaitCondition { editor.activePane === editor.panes[0] }
            inPane(1, Tags.EDITOR).performClick()
            awaitCondition { editor.activePane === editor.panes[1] }
            inPane(0, Tags.tab("README.md")).performClick()
            awaitCondition { editor.activePane === editor.panes[0] && editor.activeTab?.entry?.name == "README.md" }
            assertEquals("guide.md", app.activeNameOf(1))
        }

    @Test
    fun `Unsplit merges the panes back into one`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            openFromTree("docs/guide.md")
            splitRight("docs/guide.md")
            rightClickTab("docs/guide.md", 1)
            awaitTag(Tags.MENU_UNSPLIT).performClick()
            awaitNoTag(Tags.pane(1))
            val editor = app.project.editor
            assertFalse(editor.isSplit)
            assertEquals(listOf("guide.md", "README.md"), app.names(0))
            awaitCondition { onAllNodesWithTag(Tags.EDITOR).fetchSemanticsNodes().size == 1 }
        }

    @Test
    fun `the split entries are only offered when they apply`() =
        runComposeUiTest {
            launchWithProject(project)
            openFromTree("README.md")
            rightClickTab("README.md", 0)
            awaitTag(Tags.MENU_UNSPLIT).assertIsNotEnabled()
            onNodeWithTag(Tags.MENU_SPLIT_RIGHT).assertIsEnabled()
        }

    @Test
    fun `Close removes the tab from its own pane only`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            openFromTree("docs/guide.md")
            splitRight("docs/guide.md")
            rightClickTab("docs/guide.md", 0)
            awaitTag(Tags.MENU_CLOSE_TAB).performClick()
            awaitCondition { app.tabsOf(0).size == 1 }
            assertEquals(listOf("README.md"), app.names(0))
            assertEquals(listOf("guide.md"), app.names(1))
        }

    @Test
    fun `Close Others in a pane keeps the clicked tab and the other pane`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            openFromTree("build.gradle.kts")
            openFromTree("docs/guide.md")
            splitRight("docs/guide.md")
            rightClickTab("build.gradle.kts", 0)
            awaitTag(Tags.MENU_CLOSE_OTHER_TABS).performClick()
            awaitCondition { app.tabsOf(0).size == 1 }
            assertEquals(listOf("build.gradle.kts"), app.names(0))
            assertEquals(listOf("guide.md"), app.names(1))
        }

    @Test
    fun `Close All closes every tab of both panes`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            openFromTree("docs/guide.md")
            splitRight("docs/guide.md")
            rightClickTab("README.md", 0)
            awaitTag(Tags.MENU_CLOSE_ALL_TABS).performClick()
            awaitTag(Tags.EDITOR_EMPTY).assertIsDisplayed()
            assertFalse(app.project.editor.isSplit)
            assertTrue(app.tabsOf(0).isEmpty())
        }

    @Test
    fun `closing the last tab of a pane collapses the split`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            splitRight("README.md")
            inPane(1, Tags.tabClose("README.md")).performClick()
            awaitNoTag(Tags.pane(1))
            assertFalse(app.project.editor.isSplit)
            assertEquals(listOf("README.md"), app.names(0))
        }
}
