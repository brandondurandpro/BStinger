package org.example.kotlineditor.state

import org.example.kotlineditor.model.TextChange
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowedEditsTest {
    @Test
    fun `a change on one line reaches that line only`() {
        val renamed = TextChange(5, 10, 5, 16, "Gadget")
        assertTrue(reaches(renamed, 5, 5))
        assertTrue(reaches(renamed, 3, 7))
        assertFalse(reaches(renamed, 4, 4))
        assertFalse(reaches(renamed, 6, 9))
    }

    @Test
    fun `a change adding or removing lines reaches everything after it`() {
        val split = TextChange(5, 10, 5, 10, "\n")
        assertTrue(reaches(split, 9, 9))
        assertFalse(reaches(split, 2, 4))
        val joined = TextChange(5, 10, 6, 0, "")
        assertTrue(reaches(joined, 9, 9))
        assertTrue(reaches(joined, 6, 6))
    }
}
