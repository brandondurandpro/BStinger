package org.example.kotlineditor.plugin

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults
import org.example.kotlineditor.ui.binding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RegistriesTest {
    private fun window(name: String) =
        ToolWindowContribution(
            defaults = ToolWindowDefaults(ToolWindow(name), Dock.Bottom),
            tag = name,
            group = StripGroup.Bottom,
            icon = {},
            content = { _, _, _ -> Modifier },
        )

    @Test
    fun `tool windows come in the order they were registered and leave when their registration closes`() {
        val registry = ToolWindowRegistry()
        val first = registry.register(listOf(window("a")))
        registry.register(listOf(window("b")))
        assertEquals(listOf("a", "b"), registry.contributions.map { it.window.name })

        first.close()
        assertEquals(listOf("b"), registry.contributions.map { it.window.name })
        assertNull(registry.find(ToolWindow("a")))
    }

    @Test
    fun `a window registered twice is refused and registers nothing`() {
        val registry = ToolWindowRegistry()
        registry.register(listOf(window("a")))
        assertFailsWith<IllegalArgumentException> { registry.register(listOf(window("b"), window("a"))) }
        assertEquals(listOf("a"), registry.contributions.map { it.window.name })
    }

    @Test
    fun `shortcuts keep the order of registration within a scope`() {
        val registry = ActionRegistry()
        val one = binding<ActionContext>(setOf(Key.A)) {}
        val two = binding<ActionContext>(setOf(Key.B)) {}
        val agent = binding<ActionContext>(setOf(Key.C)) {}
        registry.register(ActionScope.Ide, listOf(one))
        val closing = registry.register(ActionScope.Ide, listOf(two))
        registry.register(ActionScope.Agent, listOf(agent))

        assertEquals(listOf(one, two), registry.bindings(ActionScope.Ide))
        assertEquals(listOf(agent), registry.bindings(ActionScope.Agent))
        closing.close()
        assertEquals(listOf(one), registry.bindings(ActionScope.Ide))
    }
}
