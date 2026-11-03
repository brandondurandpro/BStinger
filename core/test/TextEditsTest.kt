package org.example.kotlineditor.state

import org.example.kotlineditor.model.TextChange
import org.example.kotlineditor.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextEditsTest {
    @Test
    fun `a position before the change stays, inside it goes to the end of the new text, after it follows`() {
        val change = TextChange(1, 4, 2, 3, "ab\ncde")
        assertEquals(TextPos(1, 2), shifted(TextPos(1, 2), change))
        assertEquals(TextPos(2, 3), shifted(TextPos(1, 8), change))
        assertEquals(TextPos(2, 3), shifted(TextPos(2, 3), change))
        assertEquals(TextPos(2, 5), shifted(TextPos(2, 5), change))
        assertEquals(TextPos(5, 1), shifted(TextPos(5, 1), change))
    }

    @Test
    fun `a single line insertion moves what follows on its line only`() {
        val typed = TextChange(3, 2, 3, 2, "xyz")
        assertEquals(TextPos(3, 8), shifted(TextPos(3, 5), typed))
        assertEquals(TextPos(4, 5), shifted(TextPos(4, 5), typed))
    }

    @Test
    fun `removed lines pull the following ones up, inserted lines push them down`() {
        val removed = TextChange(1, 0, 4, 0, "")
        assertEquals(TextPos(3, 2), shifted(TextPos(6, 2), removed))
        val inserted = TextChange(1, 0, 1, 0, "a\nb\n")
        assertEquals(TextPos(8, 2), shifted(TextPos(6, 2), inserted))
    }

    @Test
    fun `a range follows its text and disappears when the change covers it`() {
        val change = TextChange(0, 0, 0, 0, "// added\n")
        assertEquals(TextRange(3, 1, 3, 4), shifted(TextRange(2, 1, 2, 4), change))
        assertNull(shifted(TextRange(2, 1, 2, 4), TextChange(1, 0, 3, 0, "")))
    }
}
