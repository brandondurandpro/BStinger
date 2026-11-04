package org.example.kotlineditor.unit

import org.example.kotlineditor.ui.TextClipboard
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The in-memory clipboard only: the tests must not touch the user's.
class TextClipboardTest {
    private val png = byteArrayOf(1, 2, 3)

    @BeforeTest
    fun useMemory() {
        TextClipboard.useSystem = false
    }

    @AfterTest
    fun useSystem() {
        TextClipboard.useSystem = true
    }

    @Test
    fun `copied text is read back, and it holds no image`() {
        TextClipboard.set("hello")
        assertEquals("hello", TextClipboard.get())
        assertNull(TextClipboard.image())
    }

    @Test
    fun `an image replaces the text`() {
        TextClipboard.set("hello")
        TextClipboard.setImage(png)
        assertContentEquals(png, TextClipboard.image())
        assertNull(TextClipboard.get())
    }

    @Test
    fun `text replaces the image`() {
        TextClipboard.setImage(png)
        TextClipboard.set("again")
        assertEquals("again", TextClipboard.get())
        assertNull(TextClipboard.image())
    }
}
