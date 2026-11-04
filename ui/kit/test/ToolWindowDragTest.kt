package org.example.kotlineditor.unit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowLayout
import org.example.kotlineditor.ui.ToolWindowDrag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolWindowDragTest {
    // A 1000×800 area: a quarter of the width on each side goes to the columns, the bottom 35% to the bottom dock.
    private fun dragOf(window: ToolWindow, to: Offset): ToolWindowDrag {
        val drag = ToolWindowDrag()
        drag.workspace = Rect(0f, 0f, 1000f, 800f)
        drag.start(window, Offset(500f, 400f))
        drag.moveTo(to)
        return drag
    }

    @Test
    fun `the dock under the pointer is the target`() {
        assertEquals(Dock.Left, dragOf(ToolWindow.Terminal, Offset(100f, 700f)).targetDock())
        assertEquals(Dock.Right, dragOf(ToolWindow.Terminal, Offset(900f, 100f)).targetDock())
        assertEquals(Dock.Bottom, dragOf(ToolWindow.Project, Offset(500f, 700f)).targetDock())
    }

    @Test
    fun `the middle of the editors is no target`() {
        assertNull(dragOf(ToolWindow.Terminal, Offset(500f, 300f)).targetDock())
    }

    @Test
    fun `a header pressed without moving is no drag`() {
        val drag = ToolWindowDrag()
        drag.workspace = Rect(0f, 0f, 1000f, 800f)
        drag.start(ToolWindow.Terminal, Offset(100f, 100f))
        assertNull(drag.targetDock())
    }

    @Test
    fun `no target before the workspace is laid out`() {
        val drag = ToolWindowDrag()
        drag.start(ToolWindow.Terminal, Offset.Zero)
        drag.moveTo(Offset(10f, 10f))
        assertNull(drag.targetDock())
    }

    @Test
    fun `the upper half of a column and the left half of the bottom dock go first`() {
        assertTrue(dragOf(ToolWindow.Terminal, Offset(100f, 300f)).goesFirst(Dock.Left))
        assertFalse(dragOf(ToolWindow.Terminal, Offset(100f, 500f)).goesFirst(Dock.Left))
        assertTrue(dragOf(ToolWindow.Project, Offset(400f, 700f)).goesFirst(Dock.Bottom))
        assertFalse(dragOf(ToolWindow.Project, Offset(600f, 700f)).goesFirst(Dock.Bottom))
    }

    @Test
    fun `dropping on a dock moves and shows the window there, then ends the drag`() {
        val layout = ToolWindowLayout()
        val drag = dragOf(ToolWindow.Terminal, Offset(100f, 100f))
        drag.drop(layout)
        assertEquals(Dock.Left, layout.dockOf(ToolWindow.Terminal))
        assertEquals(listOf(ToolWindow.Terminal, ToolWindow.Project), layout.visibleIn(Dock.Left))
        assertNull(drag.window)
        assertNull(drag.targetDock())
    }

    @Test
    fun `dropping in the middle or after cancelling leaves the layout as it was`() {
        val layout = ToolWindowLayout()
        val before = layout.toPreferences()
        dragOf(ToolWindow.Terminal, Offset(500f, 300f)).drop(layout)
        val cancelled = dragOf(ToolWindow.Terminal, Offset(100f, 100f))
        cancelled.cancel()
        cancelled.drop(layout)
        assertEquals(before, layout.toPreferences())
        assertNull(cancelled.window)
    }
}
