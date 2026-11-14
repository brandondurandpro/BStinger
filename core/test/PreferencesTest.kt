package org.example.kotlineditor.unit

import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.FilePreferenceStore
import org.example.kotlineditor.state.Pane
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults
import org.example.kotlineditor.state.ToolWindowLayout
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val READ_MS = 300L

class FilePreferenceStoreTest {
    private val dir = Files.createTempDirectory("kotlin-editor-prefs").toFile()
    private val file = dir.resolve("nested/preferences.properties")

    @Test
    fun `saved values are read back, creating missing directories`() {
        val values = mapOf("a" to "1", "b.c" to "x y=z:é")
        FilePreferenceStore(file).save(values)
        assertEquals(values, FilePreferenceStore(file).load())
        dir.deleteRecursively()
    }

    @Test
    fun `a missing file reads as empty`() {
        assertEquals(emptyMap(), FilePreferenceStore(file).load())
        dir.deleteRecursively()
    }

    @Test
    fun `a corrupt file reads as empty instead of failing`() {
        file.parentFile.mkdirs()
        file.writeText("broken = \\uZZZZ")
        assertEquals(emptyMap(), FilePreferenceStore(file).load())
        dir.deleteRecursively()
    }

    @Test
    fun `saving to an unwritable location does not throw`() {
        val blocker = dir.resolve("blocker").apply { writeText("a file, not a directory") }
        FilePreferenceStore(blocker.resolve("preferences.properties")).save(mapOf("a" to "1"))
        assertTrue(blocker.isFile)
        dir.deleteRecursively()
    }

    @Test
    fun `values saved while another program reads the file are kept once it is done`() {
        val store = FilePreferenceStore(file)
        store.save(mapOf("a" to "1"))
        // Windows refuses to replace a file that is open, even for reading: the reader lets go a moment later.
        val reading = Files.newInputStream(file.toPath())
        val reader =
            thread {
                Thread.sleep(READ_MS)
                reading.close()
            }
        store.save(mapOf("a" to "2"))
        reader.join()
        assertEquals(mapOf("a" to "2"), FilePreferenceStore(file).load())
        dir.deleteRecursively()
    }

    @Test
    fun `the default file is under the user's kotlin-editor directory`() {
        val path = FilePreferenceStore.defaultFile().path
        assertTrue(path.endsWith(".kotlin-editor/preferences.properties".replace('/', java.io.File.separatorChar)))
    }
}

class ToolWindowLayoutPreferencesTest {
    @Test
    fun `a customized layout is restored by a fresh one`() {
        val layout = ToolWindowLayout()
        layout.move(ToolWindow.Git, Dock.Right, first = true)
        layout.setVisible(ToolWindow.Project, false)
        layout.resize(Dock.Left, 50f)
        layout.resizeSplit(ToolWindow.Git, ToolWindow.Terminal, 0.25f)

        val restored = ToolWindowLayout().apply { restore(layout.toPreferences()) }

        assertEquals(layout.toPreferences(), restored.toPreferences())
        assertEquals(Dock.Right, restored.dockOf(ToolWindow.Git))
        assertEquals(false, restored.isVisible(ToolWindow.Project))
        assertEquals(330f, restored.sizeOf(Dock.Left))
        assertEquals(layout.weightOf(ToolWindow.Git), restored.weightOf(ToolWindow.Git))
        assertEquals(listOf(ToolWindow.Git), restored.visibleIn(Dock.Right))
    }

    @Test
    fun `missing or invalid entries keep the defaults`() {
        val defaults = ToolWindowLayout().toPreferences()
        val layout = ToolWindowLayout()
        layout.restore(
            mapOf(
                "layout.dock.Project" to "Nowhere",
                "layout.visible.Git" to "maybe",
                "layout.size.Left" to "NaN",
                "layout.weight.Git" to "-3",
                "layout.order" to "Bogus,,Terminal",
            ),
        )
        // Only the order changed: Terminal first, the windows it did not name follow (Branches, Extensions and Run
        // too, so a layout saved before those windows existed still gets them).
        val order = "Terminal,Project,Git,Branches,Extensions,Run,Network,LanguageServers"
        assertEquals(defaults + ("layout.order" to order), layout.toPreferences())
    }

    @Test
    fun `pane widths are saved, restored, and kept within their limits`() {
        val layout = ToolWindowLayout()
        layout.resizePane(Pane.HistoryBranches, 70f)
        layout.resizePane(Pane.HistoryDetails, 5_000f)
        val restored = ToolWindowLayout().apply { restore(layout.toPreferences()) }
        assertEquals(Pane.HistoryBranches.defaultWidth + 70f, restored.widthOf(Pane.HistoryBranches))
        assertEquals(900f, restored.widthOf(Pane.HistoryDetails))

        // A hand-edited or corrupted value is ignored or clamped.
        val edited =
            ToolWindowLayout().apply {
                restore(mapOf("layout.pane.HistoryBranches" to "NaN", "layout.pane.HistoryDetails" to "3"))
            }
        assertEquals(Pane.HistoryBranches.defaultWidth, edited.widthOf(Pane.HistoryBranches))
        assertEquals(120f, edited.widthOf(Pane.HistoryDetails))
    }

    @Test
    fun `restoring from nothing changes nothing`() {
        val layout = ToolWindowLayout().apply { restore(emptyMap()) }
        assertEquals(ToolWindowLayout().toPreferences(), layout.toPreferences())
    }
}

class ToolWindowLayoutContributionTest {
    private val todo = ToolWindow("plugin.todo")

    @Test
    fun `a window added by a plugin is placed where it asks and removed with its traces`() {
        val layout = ToolWindowLayout()
        layout.add(ToolWindowDefaults(todo, Dock.Right, shown = true))

        assertEquals(Dock.Right, layout.dockOf(todo))
        assertEquals(listOf(todo), layout.visibleIn(Dock.Right))

        layout.remove(todo)
        assertEquals(emptyList(), layout.visibleIn(Dock.Right))
        assertEquals(false, layout.windows.contains(todo))
        assertEquals(false, layout.toPreferences().keys.any { todo.name in it })
    }

    @Test
    fun `adding a window the layout already has keeps the arrangement of the user`() {
        val layout = ToolWindowLayout()
        layout.add(ToolWindowDefaults(todo, Dock.Right))
        layout.move(todo, Dock.Left, first = true)
        layout.add(ToolWindowDefaults(todo, Dock.Right))

        assertEquals(Dock.Left, layout.dockOf(todo))
    }
}
