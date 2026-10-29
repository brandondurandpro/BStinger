package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

@OptIn(ExperimentalTestApi::class)
class EditorTabsE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun E2eApp.tabNames() = project.editor.tabs.map { it.entry.name }

    @Test
    fun `double clicking a file opens it in a tab and shows its content`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            onNodeWithTag(Tags.tab("src/main/kotlin/App.kt")).assertIsDisplayed()
            awaitTag(Tags.line(0)).assertTextContains("package demo", substring = true)
            onNodeWithTag(Tags.line(7)).assertTextContains("val greeting = \"Hello\"", substring = true)
            onNodeWithTag(Tags.STATUS_PATH).assertTextContains("App.kt", substring = true)
            assertEquals("${project.root.fileName} – App.kt", app.state.title)
        }

    @Test
    fun `opening an already open file activates its tab instead of duplicating it`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            openFromTree("build.gradle.kts")
            assertEquals(
                "build.gradle.kts",
                app.project.editor.activeTab
                    ?.entry
                    ?.name,
            )
            onNodeWithTag(Tags.treeRow("README.md")).performDoubleClick()
            awaitCondition {
                app.project.editor.activeTab
                    ?.entry
                    ?.name == "README.md"
            }
            assertEquals(listOf("README.md", "build.gradle.kts"), app.tabNames())
        }

    @Test
    fun `clicking a tab switches the displayed file`() =
        runComposeUiTest {
            launchWithProject(project)
            openFromTree("README.md")
            openFromTree("docs/guide.md")
            awaitTag(Tags.line(1)).assertTextContains("Hello from the guide", substring = true)
            onNodeWithTag(Tags.tab("README.md")).performClick()
            awaitCondition {
                onAllNodesWithTag(Tags.line(0)).fetchSemanticsNodes().isNotEmpty() &&
                    runCatching { onNodeWithTag(Tags.line(0)).assertTextContains("# Demo", substring = true) }.isSuccess
            }
        }

    @Test
    fun `new tabs open next to the active one`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            openFromTree("build.gradle.kts")
            onNodeWithTag(Tags.tab("README.md")).performClick()
            openFromTree("docs/guide.md")
            assertEquals(listOf("README.md", "guide.md", "build.gradle.kts"), app.tabNames())
        }

    @Test
    fun `closing the active tab activates the most recently used one`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            openFromTree("build.gradle.kts")
            openFromTree("docs/guide.md")
            onNodeWithTag(Tags.tab("README.md")).performClick()
            onNodeWithTag(Tags.tab("docs/guide.md")).performClick()
            onNodeWithTag(Tags.tabClose("docs/guide.md")).performClick()
            awaitNoTag(Tags.tab("docs/guide.md"))
            assertEquals(
                "README.md",
                app.project.editor.activeTab
                    ?.entry
                    ?.name,
            )
            assertEquals(listOf("README.md", "build.gradle.kts"), app.tabNames())
        }

    @Test
    fun `closing the last tab shows the empty editor`() =
        runComposeUiTest {
            launchWithProject(project)
            openFromTree("README.md")
            onNodeWithTag(Tags.tabClose("README.md")).performClick()
            awaitTag(Tags.EDITOR_EMPTY).assertIsDisplayed()
        }

    @Test
    fun `middle click closes a tab`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            openFromTree("build.gradle.kts")
            onNodeWithTag(Tags.tab("README.md")).performMouseInput {
                moveTo(center)
                press(MouseButton.Tertiary)
                release(MouseButton.Tertiary)
            }
            awaitNoTag(Tags.tab("README.md"))
            assertEquals(listOf("build.gradle.kts"), app.tabNames())
        }

    @Test
    fun `keyboard shortcuts switch and close tabs`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            openFromTree("build.gradle.kts")
            awaitTag(Tags.EDITOR)
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionLeft) } }
            awaitCondition {
                app.project.editor.activeTab
                    ?.entry
                    ?.name == "README.md"
            }
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionRight) } }
            awaitCondition {
                app.project.editor.activeTab
                    ?.entry
                    ?.name == "build.gradle.kts"
            }
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F4) } }
            awaitNoTag(Tags.tab("build.gradle.kts"))
            assertEquals(listOf("README.md"), app.tabNames())
        }

    @Test
    fun `binary files show a message instead of their content`() =
        runComposeUiTest {
            launchWithProject(project)
            openFromTree("logo.png")
            awaitTag(Tags.EDITOR_MESSAGE).assertTextContains("Binary file", substring = true)
        }

    @Test
    fun `each tab keeps its own scroll position`() =
        runComposeUiTest {
            project.file("Long.kt", (1..2000).joinToString("\n") { "val line$it = $it" })
            val app = launchWithProject(project)
            openFromTree("Long.kt")
            awaitTag(Tags.line(0))
            editorList().performScrollToIndex(1500)
            awaitTag(Tags.line(1500))
            openFromTree("README.md")
            awaitNoTag(Tags.line(1500))
            onNodeWithTag(Tags.tab("Long.kt")).performClick()
            awaitTag(Tags.line(1500)).assertTextContains("val line1501 = 1501")
            assertTrue(
                app.project.editor.activeTab!!
                    .listState
                    .firstVisibleItemIndex in 1400..1500,
            )
        }

    @Test
    fun `a 200k line file opens fast and only visible lines are composed`() =
        runComposeUiTest {
            val lines = 200_000
            project.file("Big.kt", (1..lines).joinToString("\n") { "fun f$it(x: Int) = x * $it // comment $it" })
            val app = launchWithProject(project)
            val elapsed =
                measureTime {
                    openFromTree("Big.kt")
                    awaitTag(Tags.line(0))
                }
            assertTrue(elapsed.inWholeMilliseconds < 5_000, "opening took $elapsed")
            assertEquals(
                lines,
                app.project.editor.activeTab!!
                    .document!!
                    .lineCount,
            )
            val composed = (0 until 200).count { onAllNodesWithTag(Tags.line(it)).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(composed < 120, "composed lines: $composed")
            editorList().performScrollToIndex(lines - 1)
            awaitTag(Tags.line(lines - 1)).assertTextContains("fun f$lines(x: Int)", substring = true)
        }

    private fun androidx.compose.ui.test.ComposeUiTest.editorList() =
        onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(Tags.EDITOR)))
}
