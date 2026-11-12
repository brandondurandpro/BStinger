package org.example.kotlineditor.unit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.ui.LineLayouter
import org.example.kotlineditor.ui.LineRenderCache
import org.example.kotlineditor.ui.LineSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LineRenderCacheTest {
    private fun cache(lineCapacity: Int = 8) =
        LineRenderCache(LineLayouter(TextStyle(), Density(1f), createFontFamilyResolver()), lineCapacity = lineCapacity)

    private fun source(index: Int, text: String, startsInComment: Boolean = false, placeholder: String? = null) =
        LineSource(index, text, Language.Kotlin, startsInComment, false, placeholder != null, placeholder)

    @Test
    fun `lines with the same content share one layout, whatever their index`() {
        val cache = cache()
        val first = cache.line(source(3, "val x = 1"))
        assertSame(first, cache.line(source(3, "val x = 1")))
        assertSame(first, cache.line(source(40, "val x = 1")))
        assertEquals(1, cache.size)
    }

    @Test
    fun `the comment state and the fold placeholder are part of the content`() {
        val cache = cache()
        val plain = cache.line(source(0, "val x = 1"))
        assertNotSame(plain, cache.line(source(0, "val x = 1", startsInComment = true)))
        assertNotSame(plain, cache.line(source(0, "val x = 1", placeholder = "...}")))
        assertEquals(3, cache.size)
    }

    @Test
    fun `the least recently drawn lines are dropped first`() {
        val cache = cache(lineCapacity = 2)
        val a = cache.line(source(0, "a"))
        val b = cache.line(source(1, "b"))
        cache.line(source(0, "a")) // a is now the most recent
        cache.line(source(2, "c")) // drops b
        assertSame(a, cache.line(source(0, "a")))
        assertEquals(2, cache.size)
        assertTrue(b.isClosed, "a dropped layout frees its native memory at once")
        assertFalse(a.isClosed)
    }

    @Test
    fun `closing the cache frees every layout`() {
        val cache = cache()
        val line = cache.line(source(0, "val x = 1"))
        val number = cache.number(1, Color.Gray)
        val hint = cache.hint("3 usages")
        cache.close()
        assertTrue(line.isClosed && number.isClosed && hint.isClosed)
        assertEquals(0, cache.size)
    }

    @Test
    fun `a line number is laid out once for each color`() {
        val cache = cache()
        val dim = cache.number(7, Color.Gray)
        assertSame(dim, cache.number(7, Color.Gray))
        assertNotSame(dim, cache.number(7, Color.White))
    }
}
