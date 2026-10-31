package org.example.kotlineditor.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.model.text
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// More tabs than any test window can show side by side.
private const val MAX_TABS = 40
private const val SCROLL_ATTEMPTS = 5

@OptIn(ExperimentalTestApi::class)
class TerminalE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.screenLines(app: E2eApp): List<String> =
        app.project.terminal.active
            ?.snapshot
            ?.text()
            ?.map { it.trimEnd() }
            .orEmpty()

    // Whether a command printed [text]. The test types before the shell shows its prompt, so the output can land
    // after one (`$ text`, `C:\project>text`); lines echoing the command itself are skipped.
    private fun ComposeUiTest.printed(app: E2eApp, text: String): Boolean =
        screenLines(app).any { "echo" !in it && it.endsWith(text) }

    private fun ComposeUiTest.runCommand(command: String) {
        onNodeWithTag(Tags.TERMINAL_INPUT).performClick()
        onNodeWithTag(Tags.TERMINAL_INPUT).performTextInput(command)
        onNodeWithTag(Tags.TERMINAL_INPUT).performKeyInput { pressKey(Key.Enter) }
    }

    // The tab is first scrolled fully into view: a click at the center of a tab half hidden by the scrolled row would
    // land outside it.
    private fun ComposeUiTest.clickTab(number: Int, button: MouseButton) {
        onNodeWithTag(Tags.terminalTab(number)).performScrollTo()
        onNodeWithTag(Tags.terminalTab(number)).performMouseInput {
            moveTo(center)
            press(button)
            release(button)
        }
    }

    // Scrolls the tabs until [reached]; more than once if needed, as a scroll can end short of it.
    private fun ComposeUiTest.scrollTabsUntil(delta: Float, wheel: ScrollWheel, reached: () -> Boolean) {
        repeat(SCROLL_ATTEMPTS) {
            onNodeWithTag(Tags.TERMINAL_TABS).performMouseInput {
                moveTo(center)
                scroll(delta, wheel)
            }
            waitForIdle()
            if (reached()) return
        }
        error("The tabs did not scroll as expected")
    }

    private fun ComposeUiTest.shown(tag: String): Boolean =
        runCatching { onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    private fun E2eApp.tabNumbers() = project.terminal.tabs.map { it.number }

    private fun E2eApp.activeNumber() =
        project.terminal.active
            ?.number

    // Opens the terminal with tabs 1 to [count], tab [count] being shown, once every shell has drawn its prompt, so
    // that the row of tabs no longer moves when a click is sent.
    private fun ComposeUiTest.openTabs(app: E2eApp, count: Int) {
        onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
        awaitTag(Tags.TERMINAL_INPUT)
        repeat(count - 1) { onNodeWithTag(Tags.TERMINAL_NEW).performClick() }
        awaitCondition { app.tabNumbers() == (1..count).toList() }
        awaitCondition {
            app.project.terminal.tabs.all { tab ->
                tab.snapshot
                    ?.text()
                    ?.any { it.isNotBlank() } == true
            }
        }
    }

    private fun ComposeUiTest.clickTabClose(number: Int) {
        onNodeWithTag(Tags.terminalTab(number)).performScrollTo()
        onNodeWithTag(Tags.terminalTabClose(number)).assertIsDisplayed().performClick()
    }

    private fun ComposeUiTest.typeKeys(keys: List<Key>) {
        onNodeWithTag(Tags.TERMINAL_INPUT).performClick()
        onNodeWithTag(Tags.TERMINAL_INPUT).performKeyInput { keys.forEach { pressKey(it) } }
    }

    @Test
    fun `Alt+F12 opens a terminal that runs commands in the project directory`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TREE).performClick()
            onNodeWithTag(Tags.TREE).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.F12) } }
            awaitTag(Tags.TERMINAL_INPUT)
            runCommand("echo hello-terminal")
            awaitCondition { printed(app, "hello-terminal") }
            // Checked right away: ConPTY repaints the screen and may push earlier lines out of it. Joined without
            // spaces because after a long prompt (cmd.exe shows the whole path) the command wraps.
            val screen = screenLines(app)
            assertTrue(
                "echohello-terminal" in screen.joinToString("").filterNot(Char::isWhitespace),
                screen.joinToString("\n"),
            )
            runCommand(if (System.getProperty("os.name").startsWith("Windows")) "dir /b" else "ls")
            awaitCondition { screenLines(app).any { "README.md" in it } }
        }

    @Test
    fun `typed keys are encoded by the server engine`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
            awaitTag(Tags.TERMINAL_INPUT)
            typeKeys(
                listOf(
                    Key.E,
                    Key.C,
                    Key.H,
                    Key.O,
                    Key.Spacebar,
                    Key.K,
                    Key.E,
                    Key.Y,
                    Key.Minus,
                    Key.P,
                    Key.A,
                    Key.T,
                    Key.H,
                    Key.Enter,
                ),
            )
            awaitCondition { printed(app, "key-path") }
        }

    @Test
    fun `each tab runs its own shell`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
            awaitTag(Tags.TERMINAL_INPUT)
            runCommand("echo first-tab")
            awaitCondition { printed(app, "first-tab") }
            onNodeWithTag(Tags.TERMINAL_NEW).performClick()
            awaitCondition { app.project.terminal.tabs.size == 2 }
            runCommand("echo second-tab")
            awaitCondition { printed(app, "second-tab") }
            onNodeWithTag(Tags.terminalTab(1)).performClick()
            awaitCondition { printed(app, "first-tab") }
            clickTabClose(1)
            awaitNoTag(Tags.terminalTab(1))
            assertEquals(listOf(2), app.tabNumbers())
            awaitCondition { printed(app, "second-tab") }
        }

    @Test
    fun `every tab gets the size of the panel, not only the first one`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openTabs(app, 3)
            val grid = { tab: Int ->
                app.project.terminal.tabs
                    .first { it.number == tab }
                    .snapshot
                    ?.let { it.columns to it.rows }
            }
            awaitCondition { grid(1) != null && grid(1) == grid(3) }
            clickTab(2, MouseButton.Primary)
            awaitCondition { grid(2) == grid(1) }
        }

    @Test
    fun `the tabs and the buttons share the header of the panel`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openTabs(app, 2)
            val inHeader = hasAnyAncestor(hasTestTag(Tags.toolHeader(ToolWindow.Terminal.name)))
            for (tag in listOf(Tags.terminalTab(1), Tags.terminalTab(2), Tags.TERMINAL_NEW, Tags.TERMINAL_HIDE)) {
                onNode(hasTestTag(tag) and inHeader).assertIsDisplayed()
            }
            // Dragging a tab does not drag the panel, as dragging the bare header does.
            val dock = app.project.toolWindows.dockOf(ToolWindow.Terminal)
            onNodeWithTag(Tags.terminalTab(1)).performMouseInput {
                moveTo(center)
                press()
                moveBy(Offset(0f, -400f))
                release()
            }
            awaitCondition { app.activeNumber() == 1 }
            assertEquals(dock, app.project.toolWindows.dockOf(ToolWindow.Terminal))
        }

    @Test
    fun `with many tabs the new tab button stays reachable and the tab row scrolls`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            // Tabs are opened until they overflow (their width depends on the panel's); each one
            // opened is scrolled into view, and the button that opens them stays reachable.
            openTabs(app, 1)
            var last = 1
            while (shown(Tags.terminalTab(1))) {
                check(last < MAX_TABS) { "$MAX_TABS tabs still fit in the panel" }
                onNodeWithTag(Tags.TERMINAL_NEW).assertIsDisplayed().performClick()
                last++
                awaitCondition { app.tabNumbers().size == last && shown(Tags.terminalTab(last)) }
                waitForIdle()
            }
            // A plain mouse wheel scrolls the tabs, as does a horizontal one (touchpad, Shift+wheel).
            scrollTabsUntil(-100f, ScrollWheel.Vertical) { shown(Tags.terminalTab(1)) }
            scrollTabsUntil(100f, ScrollWheel.Horizontal) {
                shown(Tags.terminalTab(last)) && !shown(Tags.terminalTab(1))
            }
            scrollTabsUntil(-100f, ScrollWheel.Horizontal) { shown(Tags.terminalTab(1)) }
            clickTab(1, MouseButton.Primary)
            awaitCondition { app.activeNumber() == 1 }
            onNodeWithTag(Tags.TERMINAL_NEW).performClick()
            awaitCondition { shown(Tags.terminalTab(last + 1)) && !shown(Tags.terminalTab(1)) }
        }

    @Test
    fun `the close button of a tab closes that tab and keeps the others`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openTabs(app, 3)
            clickTabClose(2)
            awaitNoTag(Tags.terminalTab(2))
            assertEquals(listOf(1, 3), app.tabNumbers())
            clickTab(3, MouseButton.Tertiary)
            awaitNoTag(Tags.terminalTab(3))
            assertEquals(listOf(1), app.tabNumbers())
            clickTabClose(1)
            awaitNoTag(Tags.TERMINAL_INPUT)
            assertEquals(emptyList(), app.tabNumbers())
        }

    @Test
    fun `right clicking a terminal tab closes the other tabs`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openTabs(app, 3)
            clickTab(2, MouseButton.Secondary)
            awaitTag(Tags.TERMINAL_TAB_MENU).assertIsDisplayed()
            awaitTag(Tags.MENU_CLOSE_OTHER_TABS).performClick()
            awaitNoTag(Tags.TERMINAL_TAB_MENU)
            awaitNoTag(Tags.terminalTab(1))
            awaitNoTag(Tags.terminalTab(3))
            assertEquals(listOf(2), app.tabNumbers())
            assertEquals(2, app.activeNumber())
            // With a single tab, there are no others nor tabs on either side to close.
            clickTab(2, MouseButton.Secondary)
            awaitTag(Tags.MENU_CLOSE_OTHER_TABS).assertIsNotEnabled()
            onNodeWithTag(Tags.MENU_CLOSE_TABS_LEFT).assertIsNotEnabled()
            onNodeWithTag(Tags.MENU_CLOSE_TABS_RIGHT).assertIsNotEnabled()
            onNodeWithTag(Tags.MENU_CLOSE_TAB).assertIsEnabled()
        }

    @Test
    fun `right clicking a terminal tab closes the tabs on each side`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openTabs(app, 4)
            clickTab(2, MouseButton.Secondary)
            awaitTag(Tags.MENU_CLOSE_TABS_LEFT).performClick()
            awaitNoTag(Tags.terminalTab(1))
            assertEquals(listOf(2, 3, 4), app.tabNumbers())
            assertEquals(2, app.activeNumber())
            clickTab(3, MouseButton.Secondary)
            awaitTag(Tags.MENU_CLOSE_TABS_RIGHT).performClick()
            awaitNoTag(Tags.terminalTab(4))
            assertEquals(listOf(2, 3), app.tabNumbers())
            assertEquals(3, app.activeNumber())
            clickTab(3, MouseButton.Secondary)
            awaitTag(Tags.MENU_CLOSE_TAB).performClick()
            awaitNoTag(Tags.terminalTab(3))
            assertEquals(listOf(2), app.tabNumbers())
        }

    @Test
    fun `right clicking a terminal tab closes all tabs, or nothing when clicking elsewhere`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openTabs(app, 2)
            clickTab(1, MouseButton.Secondary)
            awaitTag(Tags.TERMINAL_TAB_MENU)
            onNodeWithTag(Tags.TERMINAL_INPUT).performMouseInput { click(bottomRight - Offset(5f, 5f)) }
            awaitNoTag(Tags.TERMINAL_TAB_MENU)
            assertEquals(listOf(1, 2), app.tabNumbers())
            clickTab(1, MouseButton.Secondary)
            awaitTag(Tags.MENU_CLOSE_ALL_TABS).performClick()
            awaitNoTag(Tags.TERMINAL_INPUT)
            assertEquals(emptyList(), app.tabNumbers())
            // A new tab can still be opened afterwards.
            onNodeWithTag(Tags.TERMINAL_NEW).performClick()
            awaitTag(Tags.terminalTab(3))
            awaitTag(Tags.TERMINAL_INPUT)
        }

    @Test
    fun `the shell exit is reported and closing the project stops the shell`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
            awaitTag(Tags.TERMINAL_INPUT)
            awaitCondition {
                app.project.terminal.active
                    ?.running == true
            }
            runCommand("exit")
            awaitCondition {
                app.project.terminal.active
                    ?.running == false
            }
            val terminalTab = app.project.terminal.active
            assertFalse(terminalTab?.running ?: true)
            val terminal = app.project.terminal
            runOnUiThread { app.state.closeProject() }
            awaitTag(Tags.WELCOME)
            awaitCondition { terminal.tabs.isEmpty() }
        }

    @Test
    fun `the terminal can be minimized and keeps its shells`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
            awaitTag(Tags.TERMINAL_INPUT)
            runCommand("echo marker-x")
            awaitCondition { printed(app, "marker-x") }
            onNodeWithTag(Tags.TERMINAL_HIDE).performClick()
            awaitNoTag(Tags.TERMINAL)
            onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
            awaitTag(Tags.TERMINAL_INPUT)
            assertTrue(printed(app, "marker-x"))
        }

    @Test
    fun `tabs are named Local, Local (2)… and a closed tab's name is given again`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openTabs(app, 3)
            onNodeWithTag(Tags.terminalTabName(1)).assertTextEquals("Local")
            onNodeWithTag(Tags.terminalTabName(2)).assertTextEquals("Local (2)")
            onNodeWithTag(Tags.terminalTabName(3)).assertTextEquals("Local (3)")
            clickTabClose(2)
            awaitNoTag(Tags.terminalTab(2))
            onNodeWithTag(Tags.TERMINAL_NEW).performClick()
            awaitTag(Tags.terminalTabName(4)).assertTextEquals("Local (2)")
            val tabs = app.project.terminal.tabs
            val names = tabs.map { it.name }
            assertEquals(listOf("Local", "Local (3)", "Local (2)"), names)
        }

    @Test
    fun `the tab keeps its short name whatever title the shell sets, and shows that title on hover`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openTabs(app, 1)
            val tabs = app.project.terminal.tabs
            val tab = tabs.single()
            // Shells set long titles (user@host: directory, the path of cmd.exe), or none at all (dash).
            onNodeWithTag(Tags.terminalTabName(1)).assertTextEquals("Local")
            onNodeWithTag(Tags.terminalTab(1)).performMouseInput { moveTo(center) }
            awaitTag(Tags.terminalTabTooltip(1))
            onNode(hasAnyAncestor(hasTestTag(Tags.terminalTabTooltip(1))) and hasText(tab.title)).assertIsDisplayed()
            onNodeWithTag(Tags.terminalTabName(1)).assertTextEquals("Local")
        }
}
