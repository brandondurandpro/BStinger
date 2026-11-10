package org.example.kotlineditor.ui

import org.example.kotlineditor.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageSpanTest {
    @Test
    fun `the usage is found in the line without its indentation`() {
        val range = TextRange(3, 12, 3, 17)
        assertEquals(4 until 9, usageSpan("        greet(1)", range))
    }

    @Test
    fun `a usage over several lines ends with the first one`() {
        val range = TextRange(0, 4, 2, 1)
        assertEquals(0 until 5, usageSpan("    greet  ", range))
    }

    @Test
    fun `an unknown line or a range outside it marks nothing`() {
        assertNull(usageSpan(null, TextRange(0, 0, 0, 3)))
        assertNull(usageSpan("    x", TextRange(0, 1, 0, 3)))
    }
}
